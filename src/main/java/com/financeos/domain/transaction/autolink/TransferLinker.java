package com.financeos.domain.transaction.autolink;

import com.financeos.api.transactionlink.dto.CreateTransactionLinkRequest;
import com.financeos.api.transactionlink.dto.MemberRef;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkOutcome.Counts;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLinkService;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pairs a DEBIT on one account with a CREDIT of exactly the same amount on another, the credit dated from
 * one day before to two days after the debit: a transfer between own accounts, or a card-bill payment when
 * the credit sits on a credit card. A pair that is the only fit for both of its sides is linked (origin
 * AUTO, debit as the anchor); every other debit with at least one fit gets a suggestion instead.
 *
 * <p>Debits on a credit-card account are never an anchor: a card purchase is spend, not a transfer.
 */
@Component
@Order(10)
public class TransferLinker implements AutoLinker {

    private static final Logger log = LoggerFactory.getLogger(TransferLinker.class);

    static final int DAYS_BEFORE = 1;
    static final int DAYS_AFTER = 2;

    private final AutoLinkCandidateLoader loader;
    private final AutoLinkStore store;
    private final TransactionLinkService linkService;
    private final TransactionTemplate isolated;

    public TransferLinker(AutoLinkCandidateLoader loader, AutoLinkStore store, TransactionLinkService linkService,
                          PlatformTransactionManager transactionManager) {
        this.loader = loader;
        this.store = store;
        this.linkService = linkService;
        this.isolated = new TransactionTemplate(transactionManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Set<SuggestionKind> ownedKinds() {
        return Set.of(SuggestionKind.TRANSFER, SuggestionKind.CC_PAYMENT);
    }

    @Override
    public AutoLinkOutcome link(UUID userId) {
        Plan plan = plan(loader.load(userId), store.loadRejectionKeys(userId));

        Map<SuggestionKind, Counts> counts = new EnumMap<>(SuggestionKind.class);
        int failed = 0;
        for (Pair pair : plan.autoLinks()) {
            SuggestionKind kind = kindFor(pair.credit());
            try {
                // Each link in its own transaction: one bad pair must not roll back the others or the suggestions.
                Boolean linked = isolated.execute(status -> {
                    Set<UUID> free = loader.freeIds(userId, List.of(pair.debit().id(), pair.credit().id()));
                    if (free.size() < 2) {
                        return false;
                    }
                    linkService.createLink(new CreateTransactionLinkRequest(LinkType.valueOf(kind.name()), null, null,
                            List.of(new MemberRef(pair.debit().id(), true), new MemberRef(pair.credit().id(), false))),
                            LinkOrigin.AUTO);
                    return true;
                });
                if (Boolean.TRUE.equals(linked)) {
                    counts.merge(kind, new Counts(1, 0), Counts::plus);
                }
            } catch (Exception e) {
                failed++;
                log.warn("Auto-link of {} / {} failed for user {}: {}", pair.debit().id(), pair.credit().id(), userId, e.getMessage());
            }
        }

        Map<SuggestionKind, Map<UUID, List<UUID>>> desired = new EnumMap<>(SuggestionKind.class);
        for (Suggestion s : plan.suggestions()) {
            desired.computeIfAbsent(s.kind(), k -> new LinkedHashMap<>()).put(s.transactionId(), s.candidateIds());
            counts.merge(s.kind(), new Counts(0, 1), Counts::plus);
        }
        store.syncSuggestions(userId, ownedKinds(), desired);
        log.info("Transfer linker finished for user {}: linked={}, failed={}, suggested={}", userId,
                counts.values().stream().mapToInt(Counts::linked).sum(), failed, plan.suggestions().size());
        return AutoLinkOutcome.of(counts, failed > 0);
    }

    // ---------------------------------------------------------------- pure planning

    record Pair(AutoLinkCandidate debit, AutoLinkCandidate credit) {
    }

    record Suggestion(SuggestionKind kind, UUID transactionId, List<UUID> candidateIds) {
    }

    record Plan(List<Pair> autoLinks, List<Suggestion> suggestions) {
    }

    /** A credit described as a refund, reversal, cashback, reward or chargeback is never one side of a transfer. */
    static final Pattern NOT_A_TRANSFER = Pattern.compile(
            "\\b(REFUND|REFUNDS|REVERSAL|REV|CASHBACK|CASH\\s+BACK|REWARD|REWARDS|CHARGEBACK)\\b", Pattern.CASE_INSENSITIVE);

    static boolean looksLikeRefundOrReward(String description) {
        return description != null && NOT_A_TRANSFER.matcher(description).find();
    }

    static SuggestionKind kindFor(AutoLinkCandidate credit) {
        return credit.accountType() == AccountType.credit_card ? SuggestionKind.CC_PAYMENT : SuggestionKind.TRANSFER;
    }

    static boolean fits(AutoLinkCandidate debit, AutoLinkCandidate credit) {
        if (debit.accountId().equals(credit.accountId())) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(debit.date(), credit.date());
        return days >= -DAYS_BEFORE && days <= DAYS_AFTER;
    }

    static Plan plan(List<AutoLinkCandidate> candidates, Set<String> rejections) {
        Map<BigDecimal, List<AutoLinkCandidate>> debitsByAmount = new HashMap<>();
        Map<BigDecimal, List<AutoLinkCandidate>> creditsByAmount = new HashMap<>();
        for (AutoLinkCandidate c : candidates) {
            if (c.type() == TransactionType.DEBIT && c.accountType() != AccountType.credit_card) {
                debitsByAmount.computeIfAbsent(c.amount().stripTrailingZeros(), k -> new ArrayList<>()).add(c);
            } else if (c.type() == TransactionType.CREDIT && !looksLikeRefundOrReward(c.merchant())) {
                creditsByAmount.computeIfAbsent(c.amount().stripTrailingZeros(), k -> new ArrayList<>()).add(c);
            }
        }

        Map<UUID, List<AutoLinkCandidate>> creditsOfDebit = new LinkedHashMap<>();
        Map<UUID, Integer> debitsOfCredit = new HashMap<>();
        Map<UUID, AutoLinkCandidate> debitById = new HashMap<>();
        for (Map.Entry<BigDecimal, List<AutoLinkCandidate>> group : debitsByAmount.entrySet()) {
            List<AutoLinkCandidate> credits = creditsByAmount.get(group.getKey());
            if (credits == null) {
                continue;
            }
            for (AutoLinkCandidate debit : group.getValue()) {
                for (AutoLinkCandidate credit : credits) {
                    if (!fits(debit, credit)
                            || AutoLinkStore.isRejected(rejections, kindFor(credit), debit.id(), credit.id())) {
                        continue;
                    }
                    creditsOfDebit.computeIfAbsent(debit.id(), k -> new ArrayList<>()).add(credit);
                    debitById.put(debit.id(), debit);
                    debitsOfCredit.merge(credit.id(), 1, Integer::sum);
                }
            }
        }

        List<Pair> autoLinks = new ArrayList<>();
        List<Suggestion> suggestions = new ArrayList<>();
        for (Map.Entry<UUID, List<AutoLinkCandidate>> e : creditsOfDebit.entrySet()) {
            AutoLinkCandidate debit = debitById.get(e.getKey());
            List<AutoLinkCandidate> credits = e.getValue();
            if (credits.size() == 1 && debitsOfCredit.get(credits.get(0).id()) == 1) {
                autoLinks.add(new Pair(debit, credits.get(0)));
                continue;
            }
            List<AutoLinkCandidate> nearestFirst = credits.stream()
                    .sorted(Comparator.comparingLong((AutoLinkCandidate c) -> Math.abs(ChronoUnit.DAYS.between(debit.date(), c.date())))
                            .thenComparing(AutoLinkCandidate::date).thenComparing(AutoLinkCandidate::id))
                    .toList();
            // Card-only candidates are a card payment; any bank credit among them makes it a transfer.
            SuggestionKind kind = nearestFirst.stream().allMatch(c -> kindFor(c) == SuggestionKind.CC_PAYMENT)
                    ? SuggestionKind.CC_PAYMENT : SuggestionKind.TRANSFER;
            suggestions.add(new Suggestion(kind, debit.id(),
                    nearestFirst.stream().map(AutoLinkCandidate::id).toList()));
        }
        return new Plan(autoLinks, suggestions);
    }
}
