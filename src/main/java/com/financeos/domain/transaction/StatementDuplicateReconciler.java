package com.financeos.domain.transaction;

import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

    private static final Logger log = LoggerFactory.getLogger(StatementDuplicateReconciler.class);

    public static final int WINDOW_DAYS = 2;

    /** One statement row paired with the existing row it repeats. */
    public record Pair(Transaction statementRow, Transaction existing, boolean certain) {}

    private final TransactionMatcher matcher;
    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final ReviewStatusManager reviewStatusManager;

    /**
     * Each pair runs in its own savepoint (nested; a plain transaction when none is open), so one failing merge
     * cannot roll back the others or the import around it. Absent in hand-built instances: pairs run directly.
     */
    private TransactionTemplate pairTx;

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

    @Autowired(required = false)
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager != null) {
            this.pairTx = new TransactionTemplate(transactionManager);
            this.pairTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        }
    }

    public static boolean isAlertOrManual(Transaction t) {
        return t.getSource() == TransactionSource.gmail_transaction_alert || t.getSource() == TransactionSource.manual;
    }

    /**
     * Pairs statement rows with existing alert/manual rows of the same account: exact amount, same direction,
     * dates within {@value #WINDOW_DAYS} days, one existing row per statement row and the reverse. Per amount
     * group the assignment matches as many rows as possible, then keeps the total date distance smallest
     * (ties: earliest created), so a late-posted series does not cross over. Pure: nothing is saved.
     * {@code excludedIds} are existing rows that are already spoken for (linked to a statement line).
     */
    public List<Pair> pair(List<Transaction> statementRows, List<Transaction> existing,
                           LocalDate periodStart, LocalDate periodEnd, Set<UUID> excludedIds) {
        Map<String, List<Transaction>> existingByKey = new HashMap<>();
        for (Transaction e : existing) {
            if (!isAlertOrManual(e) || e.getDate() == null || e.getAmount() == null) continue;
            if (excludedIds != null && excludedIds.contains(e.getId())) continue;
            existingByKey.computeIfAbsent(key(e.getType(), e.getAmount()), k -> new ArrayList<>()).add(e);
        }
        Map<String, List<Transaction>> statementByKey = new LinkedHashMap<>();
        for (Transaction s : statementRows) {
            if (s.getDate() == null || s.getAmount() == null) continue;
            statementByKey.computeIfAbsent(key(s.getType(), s.getAmount()), k -> new ArrayList<>()).add(s);
        }

        Map<Transaction, Pair> byStatement = new IdentityHashMap<>();
        for (Map.Entry<String, List<Transaction>> group : statementByKey.entrySet()) {
            List<Transaction> stmts = new ArrayList<>(group.getValue());
            stmts.sort(Comparator.comparing(Transaction::getDate));
            List<Transaction> exs = new ArrayList<>(existingByKey.getOrDefault(group.getKey(), List.of()));
            if (exs.isEmpty()) continue;
            exs.sort(Comparator.comparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            int n = Math.max(stmts.size(), exs.size());
            // Square cost matrix: a pair in the window costs its date distance (ties: keeps date order and creation order aligned, so earlier-created rows go first);
            // anything else costs more than any set of in-window pairs could, so matched pairs are maximised first.
            long none = 1_000_000_000L;
            long[][] cost = new long[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    long c = none;
                    if (i < stmts.size() && j < exs.size()) {
                        Transaction s = stmts.get(i);
                        Transaction e = exs.get(j);
                        boolean sameCard = s.getCard() == null || e.getCard() == null
                                || s.getCard().getId().equals(e.getCard().getId());
                        long diff = Math.abs(ChronoUnit.DAYS.between(s.getDate(), e.getDate()));
                        if (sameCard && diff <= WINDOW_DAYS) c = diff * 1000 + Math.abs(i - j);
                    }
                    cost[i][j] = c;
                }
            }
            int[] assignment = assign(cost);
            for (int i = 0; i < stmts.size(); i++) {
                int j = assignment[i];
                if (j >= exs.size() || cost[i][j] >= none) continue;
                Transaction s = stmts.get(i);
                Transaction e = exs.get(j);
                boolean inPeriod = periodStart != null && periodEnd != null
                        && !e.getDate().isBefore(periodStart) && !e.getDate().isAfter(periodEnd);
                byStatement.put(s, new Pair(s, e, inPeriod && matcher.sameCounterparty(s, e)));
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (Transaction s : statementRows) {
            Pair p = byStatement.get(s);
            if (p != null) pairs.add(p);
        }
        return pairs;
    }

    /** Minimum-cost assignment on a square matrix (Hungarian algorithm); returns the column chosen for each row. */
    private static int[] assign(long[][] cost) {
        int n = cost.length;
        long inf = Long.MAX_VALUE / 4;
        long[] u = new long[n + 1];
        long[] v = new long[n + 1];
        int[] p = new int[n + 1];
        int[] way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            long[] minv = new long[n + 1];
            Arrays.fill(minv, inf);
            boolean[] used = new boolean[n + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                long delta = inf;
                int j1 = 0;
                for (int j = 1; j <= n; j++) {
                    if (used[j]) continue;
                    long cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < minv[j]) {
                        minv[j] = cur;
                        way[j] = j0;
                    }
                    if (minv[j] < delta) {
                        delta = minv[j];
                        j1 = j;
                    }
                }
                for (int j = 0; j <= n; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] result = new int[n];
        for (int j = 1; j <= n; j++) result[p[j] - 1] = j - 1;
        return result;
    }

    /**
     * Merges every certain pair (statement row kept) and flags both rows of every pair that is uncertain or
     * could not be merged. Both rows must already be saved. Returns how many pairs were merged.
     */
    public int settle(List<Pair> pairs) {
        return run(pairs, false);
    }

    /** Merges only the certain pairs, each in its own transaction; a pair that cannot be merged is flagged. */
    public int mergeCertain(List<Pair> pairs) {
        return run(pairs, true);
    }

    private int run(List<Pair> pairs, boolean certainOnly) {
        int merged = 0;
        int failed = 0;
        for (Pair p : pairs) {
            if (certainOnly && !p.certain()) continue;
            if (p.certain()) {
                try {
                    if (inPairTx(() -> merge(p))) {
                        merged++;
                        continue;
                    }
                } catch (RuntimeException e) {
                    failed++;
                }
            }
            try {
                inPairTx(() -> {
                    flag(p);
                    return true;
                });
            } catch (RuntimeException e) {
                failed++;
            }
        }
        if (failed > 0) {
            log.warn("Statement duplicate pairing: {} step(s) failed out of {} pair(s); affected pairs flagged DUPLICATE_SUSPECT where possible",
                    failed, pairs.size());
        }
        return merged;
    }

    private boolean inPairTx(java.util.function.Supplier<Boolean> work) {
        if (pairTx == null) return work.get();
        return Boolean.TRUE.equals(pairTx.execute(status -> work.get()));
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

    /** Flags both rows, reloaded by id so a stale snapshot is never written back. */
    private void flag(Pair p) {
        Transaction statementRow = reload(p.statementRow());
        Transaction existing = reload(p.existing());
        reviewStatusManager.addReason(statementRow, ReviewReason.DUPLICATE_SUSPECT);
        reviewStatusManager.addReason(existing, ReviewReason.DUPLICATE_SUSPECT);
        if (transactionRepository != null) {
            transactionRepository.save(statementRow);
            transactionRepository.save(existing);
        }
    }

    private Transaction reload(Transaction t) {
        if (transactionRepository == null || t.getId() == null) return t;
        return transactionRepository.findById(t.getId()).orElse(t);
    }

    private static String key(TransactionType type, BigDecimal amount) {
        return type + "|" + amount.stripTrailingZeros().toPlainString();
    }
}
