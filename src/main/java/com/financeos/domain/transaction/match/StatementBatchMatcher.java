package com.financeos.domain.transaction.match;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCoverage;
import com.financeos.domain.statement.StatementLineProjection;
import com.financeos.domain.statement.StatementTransaction;
import com.financeos.domain.statement.StatementTransactionRepository;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Matches a whole statement at once against the account's transactions (the batch match of the matching design):
 * every line attaches to at most one transaction and every transaction gets at most one line of the statement; the
 * statement wins on date and bank text; nothing is deleted. Lines nothing pairs with are handed to the caller, which
 * creates their transactions with its own creation code; alerts the statement should have listed but does not are
 * tagged {@link ReviewReason#NOT_ON_STATEMENT}. Re-runnable for the same statement with the same result.
 * Used by the Gmail statement path, the file upload path and statement moves.
 */
@Service
public class StatementBatchMatcher {

    private static final Logger log = LoggerFactory.getLogger(StatementBatchMatcher.class);

    /** Reasons a statement line settles: none of them is added any more. */
    static final Set<ReviewReason> SETTLED_BY_LINE = EnumSet.of(ReviewReason.UNRECONCILED, ReviewReason.DUPLICATE_SUSPECT,
            ReviewReason.DATE_CORRECTED, ReviewReason.NOT_ON_STATEMENT);

    /** One statement line; {@code amount} is unsigned, {@code lineIndex} is what the statement link stores. */
    public record Line(int lineIndex, LocalDate date, BigDecimal amount, TransactionType type, String description,
                       String cardLast4, BigDecimal balanceAfter, Boolean chainValid) {

        public static Line of(int lineIndex, ParsedStatementLine l) {
            return new Line(lineIndex, l.date(), l.amount() == null ? null : l.amount().abs(),
                    TransactionType.fromLlmDirection(l.direction()), l.description(), l.cardLast4(), l.balance(), l.chainValid());
        }
    }

    /**
     * Creates and saves the transactions of lines nothing paired with, using the caller's own creation code (source,
     * source message id, guards). Returns them by line index; a line left out (e.g. already created) stays unlinked.
     */
    @FunctionalInterface
    public interface LineCreator {
        Map<Integer, Transaction> create(List<Line> unpaired);
    }

    /**
     * What one run did. {@code replacedOwnRows} (re-run only): the statement's own line-only row → the transaction its
     * line now attaches to; the caller folds the own row into that transaction and deletes it.
     */
    public record Result(int attached, int overlapAttached, int created, int tagged, int alreadyLinked,
                         List<Transaction> createdTransactions, Map<UUID, UUID> replacedOwnRows,
                         Map<UUID, Prior> replacedTargetsBefore) {
        public Result(int attached, int overlapAttached, int created, int tagged, int alreadyLinked,
                      List<Transaction> createdTransactions) {
            this(attached, overlapAttached, created, tagged, alreadyLinked, createdTransactions, Map.of(), Map.of());
        }

        public Result(int attached, int overlapAttached, int created, int tagged, int alreadyLinked,
                      List<Transaction> createdTransactions, Map<UUID, UUID> replacedOwnRows) {
            this(attached, overlapAttached, created, tagged, alreadyLinked, createdTransactions, replacedOwnRows, Map.of());
        }

        /** Lines that joined an existing transaction (attached or overlap). */
        public int matched() {
            return attached + overlapAttached;
        }
    }

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final StatementTransactionRepository statementTransactionRepository;
    private final ReviewStatusManager reviewStatusManager;
    private final StatementCoverage statementCoverage;
    private final MatchWindow matchWindow;
    private GmailProcessedMessageRepository processedMessageRepository;

    /** Alert mails point at their transaction; a re-run leaves those rows linked as they are. */
    @Autowired(required = false)
    public void setProcessedMessageRepository(GmailProcessedMessageRepository processedMessageRepository) {
        this.processedMessageRepository = processedMessageRepository;
    }

    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Foreign charges the statement filled get the ingest event (auto-linking, recurring) they skipped with no amount. */
    @Autowired(required = false)
    public void setEventPublisher(org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /** Statement-only reasons a tagged alert drops: NOT_ON_STATEMENT is then its only matching reason. */
    private static final Set<ReviewReason> REPLACED_BY_TAG = EnumSet.of(ReviewReason.UNRECONCILED,
            ReviewReason.DATE_CORRECTED, ReviewReason.DUPLICATE_SUSPECT);

    private static final int IN_CHUNK = 900;

    /** One run's result plus the transaction each attached line joined, and its fields before the attach, by line index. */
    private record Outcome(Result result, Map<Integer, Transaction> attachedByLine, Map<Integer, Prior> priorByLine) {
    }

    /**
     * A transaction's matching fields before a line attached to it (re-run only), so a caller that cannot fold the
     * replaced own row into it can undo the attach.
     */
    public record Prior(LocalDate date, String sourcedDescription, Set<ReviewReason> reasons, ReviewType reviewType,
                        java.time.Instant reviewedAt, UUID notOnStatementId, boolean amountWasNull) {
        public Prior(LocalDate date, String sourcedDescription, Set<ReviewReason> reasons, ReviewType reviewType,
                     java.time.Instant reviewedAt, UUID notOnStatementId) {
            this(date, sourcedDescription, reasons, reviewType, reviewedAt, notOnStatementId, false);
        }

        static Prior of(Transaction t) {
            return new Prior(t.getDate(), t.getSourcedDescription(),
                    t.getReviewReasons() == null ? Set.of() : Set.copyOf(t.getReviewReasons()),
                    t.getReviewType(), t.getReviewedAt(), t.getNotOnStatementId(), t.getAmount() == null);
        }

        /** Puts these fields back on the transaction (a foreign charge's filled amount goes back to unknown). */
        public void restore(Transaction t) {
            t.setDate(date);
            if (amountWasNull) t.setAmount(null);
            t.setSourcedDescription(sourcedDescription);
            if (t.getReviewReasons() == null) {
                t.setReviewReasons(new HashSet<>(reasons));
            } else {
                t.getReviewReasons().clear();
                t.getReviewReasons().addAll(reasons);
            }
            t.setReviewType(reviewType);
            t.setReviewedAt(reviewedAt);
            t.setNotOnStatementId(notOnStatementId);
        }
    }

    public StatementBatchMatcher(AccountRepository accountRepository,
                                 TransactionRepository transactionRepository,
                                 StatementTransactionRepository statementTransactionRepository,
                                 ReviewStatusManager reviewStatusManager,
                                 StatementCoverage statementCoverage,
                                 MatchWindow matchWindow) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.statementTransactionRepository = statementTransactionRepository;
        this.reviewStatusManager = reviewStatusManager;
        this.statementCoverage = statementCoverage;
        this.matchWindow = matchWindow;
    }

    /**
     * Re-runs matching for a stored statement on its (current) account, from the lines it already links (a moved
     * statement). Rows created from a line alone (no alert mail, no other statement's line) are matched again as new
     * lines: one that pairs with a transaction of the account is returned in {@link Result#replacedOwnRows} (its line
     * now on that transaction); one that does not keeps its line.
     */
    @Transactional
    public Result rerun(Statement statement, Account account) {
        UUID statementId = statement.getId();
        List<Line> lines = new ArrayList<>();
        // A legacy link with no line index is left as it is: never re-matched, its transaction never a candidate.
        Set<UUID> noIndexIds = new HashSet<>();
        for (StatementLineProjection p : statementTransactionRepository.findLinesByStatementId(statementId)) {
            if (p.lineIndex() == null) {
                if (p.transactionId() != null) noIndexIds.add(p.transactionId());
                continue;
            }
            lines.add(new Line(p.lineIndex(), p.date(), p.amount(), p.type(), p.description(),
                    null, p.balanceAfter(), p.chainValid()));
        }
        Map<Integer, Transaction> lineOnly = lineOnlyRows(statementId, account.getId());
        Set<UUID> lineOnlyIds = new HashSet<>();
        lineOnly.values().forEach(t -> lineOnlyIds.add(t.getId()));
        Set<UUID> excluded = new HashSet<>(lineOnlyIds);
        excluded.addAll(noIndexIds);
        if (!lineOnly.isEmpty()) {
            List<StatementTransaction> drop = statementTransactionRepository.findByIdStatementId(statementId).stream()
                    .filter(st -> lineOnlyIds.contains(st.getId().getTransactionId())).toList();
            statementTransactionRepository.deleteAll(drop);
            statementTransactionRepository.flush();
        }
        // A line nothing pairs with keeps its own row (re-linked, not created).
        Outcome o = run(statement, account, lines, unpaired -> {
            Map<Integer, Transaction> keep = new HashMap<>();
            for (Line l : unpaired) {
                Transaction own = lineOnly.get(l.lineIndex());
                if (own != null) keep.put(l.lineIndex(), own);
            }
            return keep;
        }, excluded);
        Map<UUID, UUID> replaced = new LinkedHashMap<>();
        Map<UUID, Prior> before = new LinkedHashMap<>();
        o.attachedByLine().forEach((index, t) -> {
            Transaction own = lineOnly.get(index);
            if (own != null) {
                replaced.put(own.getId(), t.getId());
                Prior p = o.priorByLine().get(index);
                if (p != null) before.put(t.getId(), p);
            }
        });
        Result r = o.result();
        return new Result(r.attached(), r.overlapAttached(), 0, r.tagged(), r.alreadyLinked() + r.created(), List.of(),
                replaced, before);
    }

    /** This statement's rows on the account that exist only for its line: no alert mail and no other statement's line. */
    private Map<Integer, Transaction> lineOnlyRows(UUID statementId, UUID accountId) {
        Map<UUID, Integer> indexByTxn = new HashMap<>();
        for (StatementTransaction st : statementTransactionRepository.findByIdStatementId(statementId)) {
            if (st.getLineIndex() != null) indexByTxn.put(st.getId().getTransactionId(), st.getLineIndex());
        }
        if (indexByTxn.isEmpty()) return Map.of();
        List<UUID> ids = new ArrayList<>(indexByTxn.keySet());
        Set<UUID> notLineOnly = new HashSet<>();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            List<UUID> chunk = ids.subList(i, Math.min(ids.size(), i + IN_CHUNK));
            notLineOnly.addAll(statementTransactionRepository.findLinkedToOtherStatements(chunk, statementId));
            if (processedMessageRepository != null) {
                notLineOnly.addAll(processedMessageRepository.findTransactionIdsIn(chunk));
            }
        }
        Map<Integer, Transaction> out = new HashMap<>();
        for (Transaction t : transactionRepository.findAllById(ids)) {
            if (notLineOnly.contains(t.getId()) || t.getSource() == TransactionSource.gmail_transaction_alert) continue;
            if (t.getAccount() == null || !accountId.equals(t.getAccount().getId())) continue;
            out.putIfAbsent(indexByTxn.get(t.getId()), t);
        }
        return out;
    }

    @Transactional
    public Result match(Statement statement, Account account, List<Line> lines, LineCreator creator) {
        return run(statement, account, lines, creator, Set.of()).result();
    }

    /** The batch match; {@code excluded} rows are never candidates (a re-run's own line-only rows). */
    private Outcome run(Statement statement, Account account, List<Line> lines, LineCreator creator, Set<UUID> excluded) {
        UUID statementId = statement.getId();
        UUID accountId = account.getId();
        accountRepository.lockById(accountId);
        int w = matchWindow.daysFor(account);
        int fw = matchWindow.foreignDays();

        // Lines this statement already links are done (re-run); their transactions are the statement's own.
        Set<Integer> linkedIndexes = new HashSet<>();
        Set<UUID> ownTxnIds = new HashSet<>();
        for (StatementTransaction st : statementTransactionRepository.findByIdStatementId(statementId)) {
            if (st.getLineIndex() != null) linkedIndexes.add(st.getLineIndex());
            ownTxnIds.add(st.getId().getTransactionId());
        }
        List<Line> pending = new ArrayList<>();
        for (Line l : lines) {
            if (l.date() == null || l.amount() == null || l.type() == null) continue;
            if (!linkedIndexes.contains(l.lineIndex())) pending.add(l);
        }
        pending.sort(Comparator.comparingInt(Line::lineIndex));

        LocalDate periodStart = statement.getPeriodStart();
        LocalDate periodEnd = statement.getPeriodEnd();
        LocalDate lo = min(periodStart, lines.stream().map(Line::date).filter(d -> d != null).min(LocalDate::compareTo).orElse(null));
        LocalDate hi = max(periodEnd, lines.stream().map(Line::date).filter(d -> d != null).max(LocalDate::compareTo).orElse(null));
        if (lo == null || hi == null) {
            return new Outcome(new Result(0, 0, 0, 0, linkedIndexes.size(), List.of()), Map.of(), Map.of());
        }
        // Foreign charges post up to the foreign window late, so the ledger reaches that far.
        int reach = Math.max(w, fw);
        lo = lo.minusDays(reach);
        hi = hi.plusDays(reach);

        List<Transaction> txns = new ArrayList<>(transactionRepository.findLedgerByAccountIdAndDateRange(accountId, lo, hi));
        txns.sort(Comparator.comparing(Transaction::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Transaction::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(t -> String.valueOf(t.getId())));
        Map<UUID, Set<UUID>> statementsByTxn = new HashMap<>();
        for (StatementTransaction st : statementTransactionRepository.findLinksForAccountInRange(accountId, lo, hi)) {
            statementsByTxn.computeIfAbsent(st.getId().getTransactionId(), k -> new HashSet<>()).add(st.getId().getStatementId());
        }

        List<StatementTransaction> newLinks = new ArrayList<>();
        Map<UUID, Transaction> touched = new LinkedHashMap<>();
        // Every line that joined an existing transaction (overlap or pair), so a re-run can fold the own row it replaced.
        Map<Integer, Transaction> attachedByLine = new HashMap<>();
        Map<Integer, Prior> priorByLine = new HashMap<>();

        // Overlap: the same period imported twice (Gmail and upload). A line whose transaction already carries the same
        // line of another statement joins it as this statement's line too.
        int overlapAttached = 0;
        Map<String, List<Transaction>> otherStatementRows = new HashMap<>();
        for (Transaction t : txns) {
            Set<UUID> st = statementsByTxn.get(t.getId());
            if (st == null || st.contains(statementId) || ownTxnIds.contains(t.getId())) continue;
            otherStatementRows.computeIfAbsent(overlapKey(t.getType(), t.getAmount(), t.getDate(), t.getSourcedDescription()),
                    k -> new ArrayList<>()).add(t);
        }
        List<Line> afterOverlap = new ArrayList<>();
        for (Line l : pending) {
            List<Transaction> same = otherStatementRows.get(overlapKey(l.type(), l.amount(), l.date(), l.description()));
            if (same != null && !same.isEmpty()) {
                Transaction t = same.remove(0);
                newLinks.add(new StatementTransaction(statementId, t.getId(), l.lineIndex(), l.balanceAfter(), l.chainValid()));
                priorByLine.put(l.lineIndex(), Prior.of(t));
                attachedByLine.put(l.lineIndex(), t);
                overlapAttached++;
            } else {
                afterOverlap.add(l);
            }
        }

        // Second overlap pass: the other import words its narration differently. A line pairs (pair test, cheapest
        // one-to-one) with a row linked only to other non-rejected statements of the account whose period holds the line,
        // the row itself dated inside this statement's period.
        if (!afterOverlap.isEmpty() && statementCoverage != null && periodStart != null && periodEnd != null) {
            List<Transaction> otherRows = new ArrayList<>();
            otherStatementRows.values().forEach(otherRows::addAll);
            otherRows.removeIf(t -> excluded.contains(t.getId()) || t.getDate() == null
                    || t.getDate().isBefore(periodStart) || t.getDate().isAfter(periodEnd));
            if (!otherRows.isEmpty()) {
                Map<UUID, StatementCoverage.Period> periods = new HashMap<>();
                for (StatementCoverage.Period p : statementCoverage.statements(accountId)) {
                    if (!statementId.equals(p.statementId())) periods.put(p.statementId(), p);
                }
                Map<String, List<Line>> linesByAmount = new LinkedHashMap<>();
                for (Line l : afterOverlap) {
                    linesByAmount.computeIfAbsent(groupKey(l.type(), l.amount()), k -> new ArrayList<>()).add(l);
                }
                Map<String, List<Transaction>> rowsByAmount = new HashMap<>();
                for (Transaction t : otherRows) {
                    if (t.getAmount() == null || t.getType() == null) continue;
                    rowsByAmount.computeIfAbsent(groupKey(t.getType(), t.getAmount()), k -> new ArrayList<>()).add(t);
                }
                Set<Integer> overlapPaired = new HashSet<>();
                for (Map.Entry<String, List<Line>> g : linesByAmount.entrySet()) {
                    List<Line> gl = g.getValue();
                    List<Transaction> gc = rowsByAmount.getOrDefault(g.getKey(), List.of());
                    if (gc.isEmpty()) continue;
                    Map<Integer, Integer> pairs = pair(accountId, gl, gc, w, fw, (l, t) -> {
                        Set<UUID> sts = statementsByTxn.get(t.getId());
                        if (sts == null || sts.isEmpty()) return false;
                        for (UUID sid : sts) {
                            StatementCoverage.Period p = periods.get(sid);
                            if (p == null || !p.contains(l.date())) return false;
                        }
                        return true;
                    });
                    for (Map.Entry<Integer, Integer> e : pairs.entrySet()) {
                        Line l = gl.get(e.getKey());
                        Transaction t = gc.get(e.getValue());
                        newLinks.add(new StatementTransaction(statementId, t.getId(), l.lineIndex(), l.balanceAfter(), l.chainValid()));
                        priorByLine.put(l.lineIndex(), Prior.of(t));
                        attachedByLine.put(l.lineIndex(), t);
                        overlapPaired.add(l.lineIndex());
                        overlapAttached++;
                    }
                }
                afterOverlap.removeIf(l -> overlapPaired.contains(l.lineIndex()));
            }
        }

        // Candidates: the account's transactions in [start-W, end+W] that no statement has a line for (foreign: wider W).
        List<Transaction> candidates = new ArrayList<>();
        for (Transaction t : txns) {
            if (!statementsByTxn.containsKey(t.getId()) && !ownTxnIds.contains(t.getId()) && !excluded.contains(t.getId())) {
                candidates.add(t);
            }
        }

        Set<UUID> paired = new HashSet<>();
        int attached = 0;

        // Pair per (direction, amount) group with a minimum-cost one-to-one assignment over pairs that pass canPair.
        Map<String, List<Line>> linesByGroup = new LinkedHashMap<>();
        for (Line l : afterOverlap) {
            linesByGroup.computeIfAbsent(groupKey(l.type(), l.amount()), k -> new ArrayList<>()).add(l);
        }
        Map<String, List<Transaction>> candsByGroup = new HashMap<>();
        for (Transaction t : candidates) {
            if (t.getAmount() == null || t.getType() == null) continue;
            candsByGroup.computeIfAbsent(groupKey(t.getType(), t.getAmount()), k -> new ArrayList<>()).add(t);
        }
        List<Line> unpaired = new ArrayList<>();
        for (Map.Entry<String, List<Line>> g : linesByGroup.entrySet()) {
            List<Line> gl = g.getValue();
            List<Transaction> gc = candsByGroup.getOrDefault(g.getKey(), List.of());
            Map<Integer, Integer> pairs = pair(accountId, gl, gc, w, fw, (l, t) -> true);
            for (int i = 0; i < gl.size(); i++) {
                Integer j = pairs.get(i);
                if (j == null) {
                    unpaired.add(gl.get(i));
                    continue;
                }
                Line l = gl.get(i);
                Transaction t = gc.get(j);
                priorByLine.put(l.lineIndex(), Prior.of(t));
                attach(l, t);
                paired.add(t.getId());
                attachedByLine.put(l.lineIndex(), t);
                touched.put(t.getId(), t);
                newLinks.add(new StatementTransaction(statementId, t.getId(), l.lineIndex(), l.balanceAfter(), l.chainValid()));
                attached++;
            }
        }

        // Then foreign charges still waiting for their rupee amount, per (direction, currency): against the lines the
        // amount pass left that print that currency code (and the same foreign amount when the line prints one). The
        // line fills the rupee amount on attach.
        List<Transaction> filled = new ArrayList<>();
        Map<String, List<Transaction>> foreignByGroup = new LinkedHashMap<>();
        for (Transaction t : candidates) {
            if (t.getType() == null || t.getAmount() != null || !MatchRecord.isForeign(t) || paired.contains(t.getId())) continue;
            foreignByGroup.computeIfAbsent(t.getType() + "|" + t.getOriginalCurrency().trim().toUpperCase(Locale.ROOT),
                    k -> new ArrayList<>()).add(t);
        }
        for (Map.Entry<String, List<Transaction>> g : foreignByGroup.entrySet()) {
            List<Transaction> gc = g.getValue();
            TransactionType type = gc.get(0).getType();
            String code = gc.get(0).getOriginalCurrency().trim();
            List<Line> gl = unpaired.stream()
                    .filter(l -> l.type() == type && PairRules.hasCurrencyCode(l.description(), code)).toList();
            Map<Integer, Integer> pairs = pair(accountId, gl, gc, w, fw, (l, t) -> true);
            Set<Integer> done = new HashSet<>();
            for (Map.Entry<Integer, Integer> e : pairs.entrySet()) {
                Line l = gl.get(e.getKey());
                Transaction t = gc.get(e.getValue());
                priorByLine.put(l.lineIndex(), Prior.of(t));
                attach(l, t);
                paired.add(t.getId());
                attachedByLine.put(l.lineIndex(), t);
                touched.put(t.getId(), t);
                newLinks.add(new StatementTransaction(statementId, t.getId(), l.lineIndex(), l.balanceAfter(), l.chainValid()));
                done.add(l.lineIndex());
                filled.add(t);
                attached++;
            }
            unpaired.removeIf(l -> done.contains(l.lineIndex()));
        }

        // Lines nothing paired with: the caller creates them (Confirmed), then they are linked.
        List<Transaction> created = new ArrayList<>();
        if (!unpaired.isEmpty()) {
            unpaired.sort(Comparator.comparingInt(Line::lineIndex));
            Map<Integer, Transaction> made = creator.create(unpaired);
            for (Line l : unpaired) {
                Transaction t = made == null ? null : made.get(l.lineIndex());
                if (t == null || t.getId() == null) continue;
                created.add(t);
                newLinks.add(new StatementTransaction(statementId, t.getId(), l.lineIndex(), l.balanceAfter(), l.chainValid()));
            }
        }

        // Alerts the statement should list but does not: tagged, unless dated in its last W days (the next statement
        // decides) or outside its period. A statement with no period of its own tags nothing.
        int tagged = 0;
        if (periodStart != null && periodEnd != null) {
            LocalDate tagUntil = periodEnd.minusDays(w);
            LocalDate foreignTagUntil = periodEnd.minusDays(fw);
            for (Transaction t : candidates) {
                if (paired.contains(t.getId()) || t.getSource() != TransactionSource.gmail_transaction_alert) continue;
                LocalDate until = MatchRecord.isForeign(t) ? foreignTagUntil : tagUntil;
                if (t.getDate() == null || t.getDate().isBefore(periodStart) || t.getDate().isAfter(until)) continue;
                if (t.getReviewReasons() != null && t.getReviewReasons().contains(ReviewReason.NOT_ON_STATEMENT)) continue;
                if (t.getReviewReasons() != null) t.getReviewReasons().removeAll(REPLACED_BY_TAG);
                reviewStatusManager.addReason(t, ReviewReason.NOT_ON_STATEMENT);
                t.setNotOnStatementId(statementId);
                touched.put(t.getId(), t);
                tagged++;
            }
        }

        if (!touched.isEmpty()) {
            transactionRepository.saveAll(touched.values());
        }
        if (!newLinks.isEmpty()) {
            statementTransactionRepository.saveAll(newLinks);
        }
        if (!filled.isEmpty() && eventPublisher != null && filled.get(0).getUser() != null) {
            // Auto-linking runs after commit, now that these rows have a rupee amount.
            eventPublisher.publishEvent(com.financeos.domain.transaction.autolink.TransactionsIngestedEvent.of(
                    filled.get(0).getUser().getId(), filled));
        }
        if (statementCoverage != null) {
            statementCoverage.invalidateAll();
        }
        log.info("Statement batch match: statementId={}, lines={}, alreadyLinked={}, overlap={}, attached={}, created={}, notOnStatement={}",
                statementId, lines.size(), linkedIndexes.size(), overlapAttached, attached, created.size(), tagged);
        return new Outcome(new Result(attached, overlapAttached, created.size(), tagged, linkedIndexes.size(), created),
                attachedByLine, priorByLine);
    }

    /**
     * One-to-one pairs (line index in group → candidate index in group) minimising cost over the pairs {@code allowed}
     * also accepts; ties keep date and creation order.
     */
    private static Map<Integer, Integer> pair(UUID accountId, List<Line> lines, List<Transaction> cands, int w, int foreignW,
                                              java.util.function.BiPredicate<Line, Transaction> allowed) {
        if (lines.isEmpty() || cands.isEmpty()) return Map.of();
        List<MatchRecord> lr = lines.stream().map(l -> MatchRecord.of(accountId, l)).toList();
        List<MatchRecord> cr = cands.stream().map(MatchRecord::of).toList();
        int n = Math.max(lines.size(), cands.size());
        return Assignment.pairs(lines.size(), cands.size(), (i, j) -> {
            if (!PairRules.canPair(lr.get(i), cr.get(j), w, foreignW) || !allowed.test(lines.get(i), cands.get(j))) return null;
            // Tie-break (date / creation order alignment) never outweighs one unit of cost: sum of |i-j| < n*n + 1.
            return (long) PairRules.cost(lr.get(i), cr.get(j)) * ((long) n * n + 1) + Math.abs(i - j);
        });
    }

    /**
     * Statement wins: the line's date and bank text, and for a foreign charge its rupee amount (original_* kept);
     * matching's review reasons and the not-on-statement tag go.
     */
    private void attach(Line l, Transaction t) {
        t.setDate(l.date());
        if (t.getAmount() == null || MatchRecord.isForeign(t)) {
            t.setAmount(l.amount());
        }
        if (l.description() != null && !l.description().isBlank()) {
            // Only users write Transaction.description (create/edit), so it is left as is: a null one shows this text.
            t.setSourcedDescription(l.description());
        }
        clearSettledReasons(t, reviewStatusManager);
    }

    /** Removes the reasons a statement line settles and the not-on-statement tag; NEEDS_REVIEW with none left → AUTO_REVIEWED. */
    public static void clearSettledReasons(Transaction t, ReviewStatusManager reviewStatusManager) {
        Set<ReviewReason> reasons = t.getReviewReasons();
        if (reasons != null) {
            reasons.removeAll(SETTLED_BY_LINE);
        }
        t.setNotOnStatementId(null);
        if ((reasons == null || reasons.isEmpty()) && t.getReviewType() == ReviewType.NEEDS_REVIEW) {
            reviewStatusManager.transitionTo(t, ReviewType.AUTO_REVIEWED);
        }
    }

    private static String groupKey(TransactionType type, BigDecimal amount) {
        return type + "|" + amount.stripTrailingZeros().toPlainString();
    }

    private static String overlapKey(TransactionType type, BigDecimal amount, LocalDate date, String text) {
        return type + "|" + (amount == null ? "" : amount.stripTrailingZeros().toPlainString()) + "|" + date + "|" + bankText(text);
    }

    /** Bank text compared across two imports of the same line: letters and digits only, any case. */
    static String bankText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
