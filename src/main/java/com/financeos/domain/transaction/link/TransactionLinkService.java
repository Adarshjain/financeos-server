package com.financeos.domain.transaction.link;

import com.financeos.api.transactionlink.dto.CreateTransactionLinkRequest;
import com.financeos.api.transactionlink.dto.MemberRef;
import com.financeos.api.transactionlink.dto.MemberSummary;
import com.financeos.api.transactionlink.dto.TransactionLinkResponse;
import com.financeos.api.transactionlink.dto.TransactionLinkSummary;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.category.Category;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionCategory;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@Slf4j
public class TransactionLinkService {

    private final TransactionLinkRepository transactionLinkRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;

    public TransactionLinkService(TransactionLinkRepository transactionLinkRepository,
                                  TransactionRepository transactionRepository,
                                  UserRepository userRepository) {
        this.transactionLinkRepository = transactionLinkRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
    }

    public static String roleLabel(LinkType type, boolean isAnchor) {
        return switch (type) {
            case TRANSFER -> isAnchor ? "Transfer out" : "Transfer in";
            case CC_PAYMENT -> isAnchor ? "Card bill payment" : "Payment credited";
            case REFUND -> isAnchor ? "Refunded purchase" : "Refund";
            case REVERSAL -> isAnchor ? "Reversed" : "Reversal";
            case FEE -> isAnchor ? "Parent charge" : "Fee";
            case EMI -> isAnchor ? "Purchase (EMI)" : "Installment";
        };
    }

    public TransactionLinkResponse createLink(CreateTransactionLinkRequest request, LinkOrigin createdBy) {
        UUID userId = UserContext.getCurrentUserId();
        User currentUser = userRepository.getReferenceById(userId);

        if (request.members() == null || request.members().isEmpty()) {
            throw new ValidationException("Link must contain members");
        }

        List<UUID> memberTxnIds = request.members().stream()
                .map(MemberRef::transactionId)
                .toList();

        if (memberTxnIds.size() != new HashSet<>(memberTxnIds).size()) {
            throw new ValidationException("Duplicate transactions in member list");
        }

        List<Transaction> transactions = transactionRepository.findAllByIdIn(memberTxnIds);
        Map<UUID, Transaction> txMap = transactions.stream()
                .collect(Collectors.toMap(Transaction::getId, t -> t));

        for (MemberRef ref : request.members()) {
            Transaction t = txMap.get(ref.transactionId());
            if (t == null) {
                throw new ResourceNotFoundException("Transaction", ref.transactionId());
            }
            if (t.getUser() == null || !t.getUser().getId().equals(userId)) {
                throw new ValidationException("You do not have permission to link transaction " + ref.transactionId());
            }
        }

        // Invariant: one link per transaction
        for (MemberRef ref : request.members()) {
            Optional<TransactionLink> existing = transactionLinkRepository.findByMembers_Transaction_Id(ref.transactionId());
            if (existing.isPresent()) {
                throw new ValidationException("Transaction " + ref.transactionId() + " is already in a " + existing.get().getType() + " link");
            }
        }

        // Validate exactly one anchor
        List<MemberRef> anchors = request.members().stream().filter(MemberRef::isAnchor).toList();
        if (anchors.size() != 1) {
            throw new ValidationException("Link must have exactly one anchor member");
        }
        MemberRef anchorRef = anchors.get(0);
        Transaction anchorTx = txMap.get(anchorRef.transactionId());
        List<Transaction> counterparts = request.members().stream()
                .filter(m -> !m.isAnchor())
                .map(m -> txMap.get(m.transactionId()))
                .toList();

        int totalMembers = request.members().size();

        validateLinkRules(request.type(), totalMembers, anchorTx, counterparts);

        TransactionLink link = new TransactionLink();
        link.setUser(currentUser);
        link.setType(request.type());
        link.setNote(request.note());
        link.setCreatedBy(createdBy != null ? createdBy : LinkOrigin.USER);

        for (MemberRef ref : request.members()) {
            Transaction t = txMap.get(ref.transactionId());
            TransactionLinkMember member = new TransactionLinkMember(link, t, ref.isAnchor());
            link.getMembers().add(member);
        }

        TransactionLink savedLink = transactionLinkRepository.save(link);

        // WP5: Refund category alignment
        if (Boolean.TRUE.equals(request.alignRefundCategories()) && request.type() == LinkType.REFUND) {
            Set<Category> anchorCategories = anchorTx.getCategories().stream()
                    .map(TransactionCategory::getCategory)
                    .collect(Collectors.toSet());
            for (Transaction counterpart : counterparts) {
                counterpart.setCategories(anchorCategories);
                transactionRepository.save(counterpart);
            }
        }

        return toResponse(savedLink);
    }

    private void validateLinkRules(LinkType type, int totalMembers, Transaction anchorTx, List<Transaction> counterparts) {
        switch (type) {
            case TRANSFER -> {
                if (totalMembers != 2) {
                    throw new ValidationException("TRANSFER link must have exactly 2 members");
                }
                if (anchorTx.getType() != TransactionType.DEBIT) {
                    throw new ValidationException("TRANSFER anchor must be a DEBIT transaction");
                }
                Transaction counterpart = counterparts.get(0);
                if (counterpart.getType() != TransactionType.CREDIT) {
                    throw new ValidationException("TRANSFER counterpart must be a CREDIT transaction");
                }
                if (anchorTx.getAccount().getId().equals(counterpart.getAccount().getId())) {
                    throw new ValidationException("TRANSFER anchor and counterpart must be on different accounts");
                }
            }
            case CC_PAYMENT -> {
                if (totalMembers != 2) {
                    throw new ValidationException("CC_PAYMENT link must have exactly 2 members");
                }
                if (anchorTx.getType() != TransactionType.DEBIT) {
                    throw new ValidationException("CC_PAYMENT anchor must be a DEBIT transaction");
                }
                Transaction counterpart = counterparts.get(0);
                if (counterpart.getType() != TransactionType.CREDIT) {
                    throw new ValidationException("CC_PAYMENT counterpart must be a CREDIT transaction");
                }
                if (counterpart.getAccount().getType() != AccountType.credit_card) {
                    throw new ValidationException("CC_PAYMENT counterpart must be on a credit card account");
                }
            }
            case REVERSAL -> {
                if (totalMembers != 2) {
                    throw new ValidationException("REVERSAL link must have exactly 2 members");
                }
                Transaction counterpart = counterparts.get(0);
                if (anchorTx.getType() == counterpart.getType()) {
                    throw new ValidationException("REVERSAL counterpart must have opposite direction from anchor");
                }
                if (!anchorTx.getAccount().getId().equals(counterpart.getAccount().getId())) {
                    throw new ValidationException("REVERSAL anchor and counterpart must be on the same account");
                }
            }
            case REFUND -> {
                if (totalMembers < 2) {
                    throw new ValidationException("REFUND link must have at least 2 members");
                }
                if (anchorTx.getType() != TransactionType.DEBIT) {
                    throw new ValidationException("REFUND anchor must be a DEBIT transaction");
                }
                for (Transaction counterpart : counterparts) {
                    if (counterpart.getType() != TransactionType.CREDIT) {
                        throw new ValidationException("REFUND counterparts must be CREDIT transactions");
                    }
                }
            }
            case FEE -> {
                if (totalMembers < 2) {
                    throw new ValidationException("FEE link must have at least 2 members");
                }
                if (anchorTx.getType() != TransactionType.DEBIT) {
                    throw new ValidationException("FEE anchor must be a DEBIT transaction");
                }
                for (Transaction counterpart : counterparts) {
                    if (counterpart.getType() != TransactionType.DEBIT) {
                        throw new ValidationException("FEE counterparts must be DEBIT transactions");
                    }
                }
            }
            case EMI -> {
                if (totalMembers < 2) {
                    throw new ValidationException("EMI link must have at least 2 members");
                }
                if (anchorTx.getType() != TransactionType.DEBIT) {
                    throw new ValidationException("EMI anchor must be a DEBIT transaction");
                }
                for (Transaction counterpart : counterparts) {
                    if (counterpart.getType() != TransactionType.DEBIT) {
                        throw new ValidationException("EMI counterparts must be DEBIT transactions");
                    }
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public TransactionLinkResponse getLinkById(UUID id) {
        UUID userId = UserContext.getCurrentUserId();
        TransactionLink link = transactionLinkRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TransactionLink", id));
        if (link.getUser() == null || !link.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to view this transaction link");
        }
        return toResponse(link);
    }

    @Transactional(readOnly = true)
    public List<TransactionLinkResponse> getLinksForTransaction(UUID transactionId) {
        UUID userId = UserContext.getCurrentUserId();
        Optional<TransactionLink> linkOpt = transactionLinkRepository.findByMembers_Transaction_Id(transactionId);
        if (linkOpt.isEmpty()) {
            return List.of();
        }
        TransactionLink link = linkOpt.get();
        if (link.getUser() == null || !link.getUser().getId().equals(userId)) {
            return List.of();
        }
        return List.of(toResponse(link));
    }

    public void deleteLink(UUID linkId) {
        UUID userId = UserContext.getCurrentUserId();
        TransactionLink link = transactionLinkRepository.findById(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("TransactionLink", linkId));
        if (link.getUser() == null || !link.getUser().getId().equals(userId)) {
            throw new ValidationException("You do not have permission to delete this transaction link");
        }
        transactionLinkRepository.delete(link);
    }

    public void autoDissolveLinksForDeletedTransactions(Collection<UUID> deletedTxnIds) {
        if (deletedTxnIds == null || deletedTxnIds.isEmpty()) {
            return;
        }
        List<TransactionLink> affectedLinks = transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(deletedTxnIds);
        for (TransactionLink link : affectedLinks) {
            List<TransactionLinkMember> remainingMembers = link.getMembers().stream()
                    .filter(m -> !deletedTxnIds.contains(m.getTransaction().getId()))
                    .toList();

            boolean hasAnchor = remainingMembers.stream().anyMatch(TransactionLinkMember::isAnchor);
            long counterpartCount = remainingMembers.stream().filter(m -> !m.isAnchor()).count();
            int remainingCount = remainingMembers.size();

            boolean shouldDissolve = false;
            if (remainingCount < 2) {
                shouldDissolve = true;
            } else if (!hasAnchor || counterpartCount < 1) {
                shouldDissolve = true;
            }

            if (shouldDissolve) {
                transactionLinkRepository.delete(link);
            }
        }
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<TransactionLinkSummary>> linkSummariesFor(Collection<UUID> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return Map.of();
        }
        List<TransactionLink> links = transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(transactionIds);
        Map<UUID, List<TransactionLinkSummary>> map = new HashMap<>();

        for (TransactionLink link : links) {
            int memberCount = link.getMembers().size();
            for (TransactionLinkMember member : link.getMembers()) {
                UUID txId = member.getTransaction().getId();
                if (transactionIds.contains(txId)) {
                    String label = roleLabel(link.getType(), member.isAnchor());
                    TransactionLinkSummary summary = new TransactionLinkSummary(link.getId(), link.getType(), label, memberCount);
                    map.computeIfAbsent(txId, k -> new ArrayList<>()).add(summary);
                }
            }
        }
        return map;
    }

    public TransactionLinkResponse toResponse(TransactionLink link) {
        List<MemberSummary> memberSummaries = link.getMembers().stream()
                .map(m -> {
                    Transaction t = m.getTransaction();
                    BigDecimal signedAmount = t.getType() == TransactionType.DEBIT
                            ? t.getAmount().negate()
                            : t.getAmount();
                    String desc = t.getDescription() != null ? t.getDescription() : t.getSourcedDescription();
                    String role = roleLabel(link.getType(), m.isAnchor());
                    return new MemberSummary(
                            t.getId(),
                            t.getDate(),
                            signedAmount,
                            desc,
                            t.getAccount().getId(),
                            m.isAnchor(),
                            role
                    );
                })
                .toList();

        return new TransactionLinkResponse(
                link.getId(),
                link.getType(),
                link.getNote(),
                link.getCreatedBy(),
                link.getCreatedAt(),
                memberSummaries
        );
    }
}
