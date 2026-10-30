package com.financeos.domain.transaction.autolink;

import com.financeos.api.linksuggestion.dto.LinkSuggestionResponse;
import com.financeos.api.linksuggestion.dto.LinkSuggestionResponse.TransactionSummary;
import com.financeos.api.transactionlink.dto.CreateTransactionLinkRequest;
import com.financeos.api.transactionlink.dto.MemberRef;
import com.financeos.api.transactionlink.dto.TransactionLinkResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLinkService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists, accepts and dismisses the stored link suggestions of one user. */
@Service
public class LinkSuggestionService {

    static final int IN_CHUNK = 500;

    private final AutoLinkStore store;
    private final TransactionRepository transactionRepository;
    private final TransactionLinkService linkService;

    public LinkSuggestionService(AutoLinkStore store, TransactionRepository transactionRepository,
                                 TransactionLinkService linkService) {
        this.store = store;
        this.transactionRepository = transactionRepository;
        this.linkService = linkService;
    }

    /**
     * The suggestions still worth showing: the transaction and at least one candidate still exist, belong to
     * the user and are unlinked; candidate lists are trimmed to those. One query per 500 ids, never per row.
     */
    @Transactional(readOnly = true)
    public List<AutoLinkStore.SuggestionRow> openRows(UUID userId) {
        List<AutoLinkStore.SuggestionRow> rows = store.listSuggestions(userId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (AutoLinkStore.SuggestionRow row : rows) {
            ids.add(row.transactionId());
            ids.addAll(row.candidateIds());
        }
        Set<UUID> live = store.liveTransactionIds(userId, ids);
        List<AutoLinkStore.SuggestionRow> out = new ArrayList<>();
        for (AutoLinkStore.SuggestionRow row : rows) {
            if (!live.contains(row.transactionId())) {
                continue;
            }
            List<UUID> candidates = row.candidateIds().stream().filter(live::contains).toList();
            if (!candidates.isEmpty()) {
                out.add(new AutoLinkStore.SuggestionRow(row.id(), row.kind(), row.transactionId(), candidates));
            }
        }
        return out;
    }

    /** Open suggestions per kind: the same set {@link #list} returns, so the Inbox count matches the GET. */
    @Transactional(readOnly = true)
    public Map<SuggestionKind, Integer> openCounts(UUID userId) {
        Map<SuggestionKind, Integer> out = new java.util.EnumMap<>(SuggestionKind.class);
        for (AutoLinkStore.SuggestionRow row : openRows(userId)) {
            out.merge(row.kind(), 1, Integer::sum);
        }
        return out;
    }

    /** The user's open suggestions with their transactions, loaded in a few batched queries. */
    @Transactional(readOnly = true)
    public List<LinkSuggestionResponse> list(UUID userId) {
        List<AutoLinkStore.SuggestionRow> rows = openRows(userId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (AutoLinkStore.SuggestionRow row : rows) {
            ids.add(row.transactionId());
            ids.addAll(row.candidateIds());
        }
        Map<UUID, Transaction> byId = load(userId, ids);
        List<LinkSuggestionResponse> out = new ArrayList<>();
        for (AutoLinkStore.SuggestionRow row : rows) {
            Transaction main = byId.get(row.transactionId());
            List<TransactionSummary> candidates = row.candidateIds().stream()
                    .filter(byId::containsKey).map(id -> summary(byId.get(id))).toList();
            if (main != null && !candidates.isEmpty()) {
                out.add(new LinkSuggestionResponse(row.id(), row.kind(), summary(main), candidates));
            }
        }
        return out;
    }

    /** Creates the link with origin USER and removes the suggestion. */
    @Transactional
    public TransactionLinkResponse accept(UUID userId, UUID suggestionId, UUID candidateId) {
        AutoLinkStore.SuggestionRow row = store.findSuggestion(userId, suggestionId)
                .orElseThrow(() -> new ResourceNotFoundException("LinkSuggestion", suggestionId));
        if (!row.candidateIds().contains(candidateId)) {
            throw new ValidationException("That transaction is not a candidate of this suggestion");
        }
        Map<UUID, Transaction> txns = load(userId, List.of(row.transactionId(), candidateId));
        Transaction main = txns.get(row.transactionId());
        Transaction candidate = txns.get(candidateId);
        if (main == null || candidate == null) {
            throw new ResourceNotFoundException("Transaction", candidate == null ? candidateId : row.transactionId());
        }

        // Transfer and card-payment suggestions key on the debit; a refund keys on the credit.
        LinkType type;
        Transaction debit;
        Transaction credit;
        if (row.kind() == SuggestionKind.REFUND) {
            type = LinkType.REFUND;
            debit = candidate;
            credit = main;
        } else {
            type = kindOf(candidate) == SuggestionKind.CC_PAYMENT ? LinkType.CC_PAYMENT : LinkType.TRANSFER;
            debit = main;
            credit = candidate;
        }
        TransactionLinkResponse link = linkService.createLink(new CreateTransactionLinkRequest(type, null, null,
                List.of(new MemberRef(debit.getId(), true), new MemberRef(credit.getId(), false))), LinkOrigin.USER);
        store.deleteSuggestion(userId, suggestionId);
        return link;
    }

    /** Remembers the transaction against each candidate, so none of those pairings is proposed again, and removes the row. */
    @Transactional
    public void dismiss(UUID userId, UUID suggestionId) {
        AutoLinkStore.SuggestionRow row = store.findSuggestion(userId, suggestionId)
                .orElseThrow(() -> new ResourceNotFoundException("LinkSuggestion", suggestionId));
        Map<UUID, Transaction> candidates = load(userId, row.candidateIds());
        List<AutoLinkStore.Rejection> rejections = new ArrayList<>();
        for (UUID candidateId : row.candidateIds()) {
            Transaction candidate = candidates.get(candidateId);
            // The linkers check the kind the pairing would link as, which depends on the candidate's account.
            SuggestionKind kind = row.kind() == SuggestionKind.REFUND || candidate == null ? row.kind() : kindOf(candidate);
            rejections.add(new AutoLinkStore.Rejection(kind, row.transactionId(), candidateId));
        }
        store.addRejections(userId, rejections);
        store.deleteSuggestion(userId, suggestionId);
    }

    private static SuggestionKind kindOf(Transaction creditSide) {
        return creditSide.getAccount().getType() == AccountType.credit_card ? SuggestionKind.CC_PAYMENT : SuggestionKind.TRANSFER;
    }

    private Map<UUID, Transaction> load(UUID userId, java.util.Collection<UUID> ids) {
        Map<UUID, Transaction> out = new HashMap<>();
        List<UUID> list = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int i = 0; i < list.size(); i += IN_CHUNK) {
            for (Transaction t : transactionRepository.findAllByIdInAndUserId(list.subList(i, Math.min(i + IN_CHUNK, list.size())), userId)) {
                out.putIfAbsent(t.getId(), t);
            }
        }
        return out;
    }

    private static TransactionSummary summary(Transaction t) {
        String description = t.getDescription() != null ? t.getDescription() : t.getSourcedDescription();
        return new TransactionSummary(t.getId(), t.getDate(), t.getAmount(), t.getType(), description, t.getAccount().getName());
    }
}
