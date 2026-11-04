package com.financeos.domain.transaction;

import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pairs the rows of a statement import with the alert or manual rows that already record the same payments.
 * A pair is certain (same merchant, existing row inside the statement period) and gets merged, the statement
 * row winning, or uncertain and stays flagged DUPLICATE_SUSPECT for the user. Statement rows are never paired
 * with other statement rows here.
 */
@Component
public class StatementDuplicateReconciler {

    public static final int WINDOW_DAYS = 2;

    /** One statement row paired with the existing row it repeats. */
    public record Pair(Transaction statementRow, Transaction existing, boolean certain) {}

    private final TransactionMatcher matcher;
    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final ReviewStatusManager reviewStatusManager;

    @Autowired
    public StatementDuplicateReconciler(TransactionMatcher matcher,
                                        TransactionService transactionService,
                                        TransactionRepository transactionRepository,
                                        GmailProcessedMessageRepository processedMessageRepository,
                                        ReviewStatusManager reviewStatusManager) {
        this.matcher = matcher;
        this.transactionService = transactionService;
        this.transactionRepository = transactionRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.reviewStatusManager = reviewStatusManager;
    }

    public static boolean isAlertOrManual(Transaction t) {
        return t.getSource() == TransactionSource.gmail_transaction_alert || t.getSource() == TransactionSource.manual;
    }

    /**
     * Pairs statement rows with existing alert/manual rows of the same account: exact amount, same direction,
     * dates within {@value #WINDOW_DAYS} days, one existing row per statement row and the reverse, closest date
     * first (ties: earliest created). Pure: nothing is saved. {@code excludedIds} are existing rows that are
     * already spoken for (linked to a statement line).
     */
    public List<Pair> pair(List<Transaction> statementRows, List<Transaction> existing,
                           LocalDate periodStart, LocalDate periodEnd, Set<UUID> excludedIds) {
        Map<String, List<Transaction>> byKey = new HashMap<>();
        for (Transaction e : existing) {
            if (!isAlertOrManual(e) || e.getDate() == null || e.getAmount() == null) continue;
            if (excludedIds != null && excludedIds.contains(e.getId())) continue;
            byKey.computeIfAbsent(key(e.getType(), e.getAmount()), k -> new ArrayList<>()).add(e);
        }

        record Candidate(Transaction stmt, Transaction ex, long diff) {}
        List<Candidate> candidates = new ArrayList<>();
        for (Transaction s : statementRows) {
            if (s.getDate() == null || s.getAmount() == null) continue;
            for (Transaction e : byKey.getOrDefault(key(s.getType(), s.getAmount()), List.of())) {
                if (s.getCard() != null && e.getCard() != null && !s.getCard().getId().equals(e.getCard().getId())) continue;
                long diff = Math.abs(ChronoUnit.DAYS.between(s.getDate(), e.getDate()));
                if (diff <= WINDOW_DAYS) candidates.add(new Candidate(s, e, diff));
            }
        }
        candidates.sort(Comparator.comparingLong(Candidate::diff)
                .thenComparing(c -> c.ex().getCreatedAt(), Comparator.nullsLast(Comparator.naturalOrder())));

        Set<Transaction> usedStatement = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Set<Transaction> usedExisting = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Pair> pairs = new ArrayList<>();
        for (Candidate c : candidates) {
            if (usedStatement.contains(c.stmt()) || usedExisting.contains(c.ex())) continue;
            usedStatement.add(c.stmt());
            usedExisting.add(c.ex());
            boolean inPeriod = periodStart != null && periodEnd != null
                    && !c.ex().getDate().isBefore(periodStart) && !c.ex().getDate().isAfter(periodEnd);
            pairs.add(new Pair(c.stmt(), c.ex(), inPeriod && matcher.sameCounterparty(c.stmt(), c.ex())));
        }
        return pairs;
    }

    /**
     * Merges every certain pair (statement row kept) and flags both rows of every pair that is uncertain or
     * could not be merged. Both rows must already be saved. Returns how many pairs were merged.
     */
    @Transactional
    public int settle(List<Pair> pairs) {
        int merged = 0;
        for (Pair p : pairs) {
            if (p.certain() && merge(p)) {
                merged++;
            } else {
                flag(p);
            }
        }
        return merged;
    }

    /** Merges only the certain pairs; returns the pairs it could not merge, flagged. */
    @Transactional
    public int mergeCertain(List<Pair> pairs) {
        int merged = 0;
        for (Pair p : pairs) {
            if (!p.certain()) continue;
            if (merge(p)) {
                merged++;
            } else {
                flag(p);
            }
        }
        return merged;
    }

    private boolean merge(Pair p) {
        if (transactionService == null || p.statementRow().getId() == null || p.existing().getId() == null) {
            return false;
        }
        UUID keepId = p.statementRow().getId();
        UUID deleteId = p.existing().getId();
        GmailProcessedMessage mail = processedMessageRepository != null
                ? processedMessageRepository.findByTransactionId(deleteId).orElse(null) : null;
        if (!transactionService.mergeStatementDuplicate(keepId, deleteId)) {
            return false;
        }
        // The absorbed alert's mail now stands for the statement row, so a later alert is not paired with it twice.
        if (mail != null) {
            mail.setTransaction(transactionRepository.getReferenceById(keepId));
            processedMessageRepository.save(mail);
        }
        return true;
    }

    private void flag(Pair p) {
        reviewStatusManager.addReason(p.statementRow(), ReviewReason.DUPLICATE_SUSPECT);
        reviewStatusManager.addReason(p.existing(), ReviewReason.DUPLICATE_SUSPECT);
        if (transactionRepository != null) {
            transactionRepository.save(p.statementRow());
            transactionRepository.save(p.existing());
        }
    }

    private static String key(TransactionType type, BigDecimal amount) {
        return type + "|" + amount.stripTrailingZeros().toPlainString();
    }
}
