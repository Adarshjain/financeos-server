package com.financeos.domain.transaction.match;

import com.financeos.core.security.UserContextHelper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.obligation.ObligationRefService;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementMoveService;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedStatus;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One-off history re-match (matching redesign wave 4): brings an account's stored rows to the state the new pipeline
 * would have produced. Per account, under its lock and in its own database transaction: (1) foreign alerts stored as
 * rupees of the foreign number become foreign charges, (2) the legacy reasons UNRECONCILED, DUPLICATE_SUSPECT and
 * DATE_CORRECTED go, (3) every non-rejected statement is re-matched oldest first through the statement move's code
 * path ({@link StatementBatchMatcher#rerun} + {@link StatementMoveService#foldReplacedOwnRows}), (4) alert mails the
 * statement cutoff skipped as covered are queued for the normal drain again. A dry run runs the same code and rolls
 * the transaction back, so nothing is written and no after-commit listener runs.
 */
@Service
public class HistoryRematchService {

    private static final Logger log = LoggerFactory.getLogger(HistoryRematchService.class);

    /** Reasons the new pipeline never adds. */
    static final Set<ReviewReason> LEGACY_REASONS = EnumSet.of(ReviewReason.UNRECONCILED, ReviewReason.DUPLICATE_SUSPECT,
            ReviewReason.DATE_CORRECTED);
    /** A foreign alert's statement line is at most this many days from it. */
    static final int FOREIGN_LINE_DAYS = 7;
    /** Per-account cap of each listed item kind; counts are always complete. */
    static final int LIST_CAP = 200;
    static final int REQUEUE_LIST_CAP = 50;
    private static final int IN_CHUNK = 900;

    private static final Set<String> FOREIGN_CODES = Currency.getAvailableCurrencies().stream()
            .map(Currency::getCurrencyCode).filter(c -> !"INR".equals(c)).collect(Collectors.toUnmodifiableSet());

    private final EntityManager entityManager;
    private final TransactionTemplate tx;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ReviewStatusManager reviewStatusManager;
    private final StatementBatchMatcher batchMatcher;
    private final StatementMoveService moveService;
    private ObligationRefService obligationRefService;

    public HistoryRematchService(EntityManager entityManager, PlatformTransactionManager transactionManager,
                                 AccountRepository accountRepository, TransactionRepository transactionRepository,
                                 ReviewStatusManager reviewStatusManager, StatementBatchMatcher batchMatcher,
                                 StatementMoveService moveService) {
        this.entityManager = entityManager;
        this.tx = new TransactionTemplate(transactionManager);
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.reviewStatusManager = reviewStatusManager;
        this.batchMatcher = batchMatcher;
        this.moveService = moveService;
    }

    /** Only to name why a merge was refused in the report (the merge itself runs its own check). */
    @Autowired(required = false)
    public void setObligationRefService(@Lazy ObligationRefService obligationRefService) {
        this.obligationRefService = obligationRefService;
    }

    // ------------------------------------------------------------------ report shapes

    public record Side(String id, String date, String amount, String description, String source) {
    }

    public record StatementRef(String id, String periodStart, String periodEnd, String source) {
    }

    public record MergeItem(Side keep, Side absorb, StatementRef statement) {
    }

    public record RefusedMerge(Side keep, Side absorb, StatementRef statement, String reason) {
    }

    public record TagItem(String id, String date, String amount, String description, StatementRef statement) {
    }

    public record ForeignItem(String id, String date, String amount, String description, String currency,
                              String originalAmount, String via) {
    }

    public record ReviewChange(String id, String date, String amount, String description, List<String> reasonsCleared,
                               String reviewTypeBefore, String reviewTypeAfter) {
    }

    public record RequeueItem(String id, String subject, String mailDate, boolean templateRead) {
    }

    public record Doubt(String kind, String id, String detail) {
    }

    /** Everything one account's run did (dry run: would do). Lists are capped; the counts are not. */
    public record AccountReport(String userId, String accountId, String name, String type, String last4,
                                Map<String, Integer> counts,
                                List<ForeignItem> foreignConversions,
                                Map<String, Integer> reasonsCleared,
                                List<ReviewChange> reviewTypeChanges,
                                List<MergeItem> merges,
                                List<RefusedMerge> refusedMerges,
                                List<TagItem> notOnStatementTags,
                                List<RequeueItem> requeued,
                                List<Doubt> doubts,
                                String error) {

        /** True when the run changed (or would change) nothing (the statements count is what it looked at). */
        public boolean isEmpty() {
            return error == null && counts.entrySet().stream()
                    .filter(e -> !"statements".equals(e.getKey())).allMatch(e -> e.getValue() == 0);
        }
    }

    // ------------------------------------------------------------------ entry points

    /** The (userId, accountId) pairs to run, optionally narrowed to one user and / or one account. */
    @SuppressWarnings("unchecked")
    public List<UUID[]> targets(UUID userId, UUID accountId) {
        return tx.execute(status -> {
            StringBuilder q = new StringBuilder("SELECT a.user.id, a.id FROM Account a WHERE 1 = 1");
            if (userId != null) q.append(" AND a.user.id = :userId");
            if (accountId != null) q.append(" AND a.id = :accountId");
            q.append(" ORDER BY a.user.id, a.createdAt, a.id");
            var query = entityManager.createQuery(q.toString(), Object[].class);
            if (userId != null) query.setParameter("userId", userId);
            if (accountId != null) query.setParameter("accountId", accountId);
            List<UUID[]> out = new ArrayList<>();
            for (Object[] row : query.getResultList()) {
                out.add(new UUID[]{(UUID) row[0], (UUID) row[1]});
            }
            return out;
        });
    }

    /**
     * Runs the four steps for one account in its own transaction, as the account's user. {@code dryRun} rolls the
     * transaction back after recording what it did. A failure rolls the account back and is reported, not thrown.
     */
    public AccountReport runAccount(UUID userId, UUID accountId, boolean dryRun) {
        String previous = MDC.get("rematchDryRun");
        MDC.put("rematchDryRun", String.valueOf(dryRun));
        try {
            return UserContextHelper.callAs(userId, () -> {
                try {
                    return tx.execute(status -> {
                        AccountReport r = runSteps(userId, accountId);
                        if (dryRun) {
                            status.setRollbackOnly();
                        }
                        return r;
                    });
                } catch (RuntimeException e) {
                    log.warn("History re-match failed and was rolled back: accountId={}, dryRun={}", accountId, dryRun, e);
                    return failed(userId, accountId, e);
                }
            });
        } finally {
            if (previous == null) MDC.remove("rematchDryRun");
            else MDC.put("rematchDryRun", previous);
        }
    }

    private AccountReport failed(UUID userId, UUID accountId, RuntimeException e) {
        return new AccountReport(String.valueOf(userId), String.valueOf(accountId), null, null, null, Map.of(),
                List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    // ------------------------------------------------------------------ the steps

    private AccountReport runSteps(UUID userId, UUID accountId) {
        Account account = accountRepository.lockById(accountId).orElseThrow(
                () -> new IllegalStateException("Account not found: " + accountId));
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Doubt> doubts = new ArrayList<>();

        List<ForeignItem> foreign = new ArrayList<>();
        counts.put("foreignConverted", repairForeign(accountId, foreign, doubts));
        entityManager.flush();

        Map<String, Integer> reasonsCleared = new LinkedHashMap<>();
        List<ReviewChange> reviewChanges = new ArrayList<>();
        int[] cleared = clearLegacyReasons(accountId, reasonsCleared, reviewChanges);
        counts.put("reasonsClearedRows", cleared[0]);
        counts.put("reviewTypeChanged", cleared[1]);
        entityManager.flush();

        List<MergeItem> merges = new ArrayList<>();
        List<RefusedMerge> refused = new ArrayList<>();
        List<TagItem> tags = new ArrayList<>();
        int[] rematch = rematchStatements(account, merges, refused, tags);
        counts.put("statements", rematch[0]);
        counts.put("merged", rematch[1]);
        counts.put("refusedMerges", rematch[2]);
        counts.put("notOnStatementTagged", rematch[3]);
        entityManager.flush();

        List<RequeueItem> requeued = new ArrayList<>();
        int[] rq = requeueCoveredSkips(accountId, requeued);
        counts.put("requeued", rq[0]);
        counts.put("requeuedTemplateRead", rq[1]);
        counts.put("requeuedNeedsAi", rq[0] - rq[1]);
        counts.put("doubts", doubts.size());
        entityManager.flush();

        return new AccountReport(String.valueOf(userId), String.valueOf(accountId), account.getName(),
                account.getType() == null ? null : account.getType().name(), last4(account), counts,
                cap(foreign, LIST_CAP), reasonsCleared, cap(reviewChanges, LIST_CAP), cap(merges, LIST_CAP),
                cap(refused, LIST_CAP), cap(tags, LIST_CAP), cap(requeued, REQUEUE_LIST_CAP), cap(doubts, LIST_CAP), null);
    }

    /**
     * Step 1. Alert rows with a rupee amount, no statement line of their own and no foreign currency yet that are
     * really foreign: (a) their mail was read in a currency other than INR, or (b) a statement line of the account
     * within {@link #FOREIGN_LINE_DAYS} days (same direction, same card or none) prints a currency code beside a
     * decimal equal to the row's amount. The stored number becomes the original amount; the rupee amount is unknown
     * until a statement line fills it. Disagreeing or ambiguous evidence is reported, never converted.
     */
    private int repairForeign(UUID accountId, List<ForeignItem> out, List<Doubt> doubts) {
        List<Transaction> rows = entityManager.createQuery(
                        "SELECT t FROM Transaction t WHERE t.account.id = :a AND t.amount IS NOT NULL "
                                + "AND (t.originalCurrency IS NULL OR UPPER(t.originalCurrency) = 'INR') "
                                + "AND (t.source = :alert OR EXISTS (SELECT 1 FROM GmailProcessedMessage g WHERE g.transaction = t)) "
                                + "AND NOT EXISTS (SELECT 1 FROM StatementTransaction st WHERE st.id.transactionId = t.id)",
                        Transaction.class)
                .setParameter("a", accountId)
                .setParameter("alert", TransactionSource.gmail_transaction_alert)
                .getResultList();
        if (rows.isEmpty()) return 0;

        // (a) what each row's mail was read as
        Map<UUID, List<Object[]>> mailReads = new HashMap<>();
        List<UUID> ids = rows.stream().map(Transaction::getId).toList();
        for (int i = 0; i < ids.size(); i += IN_CHUNK) {
            List<Object[]> res = entityManager.createQuery(
                            "SELECT g.transaction.id, g.extractCurrency, g.extractAmount FROM GmailProcessedMessage g "
                                    + "WHERE g.transaction.id IN :ids", Object[].class)
                    .setParameter("ids", ids.subList(i, Math.min(ids.size(), i + IN_CHUNK)))
                    .getResultList();
            for (Object[] r : res) {
                mailReads.computeIfAbsent((UUID) r[0], k -> new ArrayList<>()).add(r);
            }
        }

        // (b) the account's statement lines that print a foreign code beside a decimal
        List<ForeignLine> lines = new ArrayList<>();
        List<Object[]> lineRows = entityManager.createQuery(
                        "SELECT t.id, t.date, t.type, c.last4, COALESCE(t.sourcedDescription, t.description) "
                                + "FROM Transaction t LEFT JOIN t.card c WHERE t.account.id = :a AND EXISTS ("
                                + "SELECT 1 FROM StatementTransaction st, Statement s WHERE st.id.transactionId = t.id "
                                + "AND s.id = st.id.statementId AND (s.verdict IS NULL OR s.verdict <> :rejected))",
                        Object[].class)
                .setParameter("a", accountId)
                .setParameter("rejected", StatementVerdict.REJECTED)
                .getResultList();
        for (Object[] r : lineRows) {
            String text = (String) r[4];
            Map<String, BigDecimal> printed = printedForeignAmounts(text);
            if (!printed.isEmpty() && r[1] != null) {
                lines.add(new ForeignLine((UUID) r[0], (LocalDate) r[1], (TransactionType) r[2], (String) r[3], printed));
            }
        }

        int converted = 0;
        for (Transaction t : rows) {
            Set<String> mailCodes = new TreeSet<>();
            boolean mailAmountDiffers = false;
            boolean mailReadInr = false;
            for (Object[] r : mailReads.getOrDefault(t.getId(), List.of())) {
                String code = r[1] == null ? null : ((String) r[1]).trim().toUpperCase(Locale.ROOT);
                if ("INR".equals(code)) mailReadInr = true;
                if (MatchRecord.isForeignCode(code)) {
                    mailCodes.add(code);
                    BigDecimal mailAmount = (BigDecimal) r[2];
                    if (mailAmount != null && mailAmount.abs().compareTo(t.getAmount().abs()) != 0) mailAmountDiffers = true;
                }
            }
            String cardLast4 = t.getCard() != null ? t.getCard().getLast4() : null;
            Set<String> lineCodes = new TreeSet<>();
            for (ForeignLine l : lines) {
                if (l.type() != t.getType() || t.getDate() == null) continue;
                if (Math.abs(ChronoUnit.DAYS.between(l.date(), t.getDate())) > FOREIGN_LINE_DAYS) continue;
                if (cardLast4 != null && l.cardLast4() != null && !cardLast4.equals(l.cardLast4())) continue;
                l.printed().forEach((code, amount) -> {
                    if (amount.compareTo(t.getAmount().abs()) == 0) lineCodes.add(code);
                });
            }
            if (mailCodes.isEmpty() && lineCodes.isEmpty()) continue;

            String id = String.valueOf(t.getId());
            if (mailCodes.size() > 1 || lineCodes.size() > 1) {
                doubts.add(new Doubt("FOREIGN_AMBIGUOUS", id, "mail " + mailCodes + ", statement lines " + lineCodes
                        + "; " + describe(t)));
                continue;
            }
            if (!mailCodes.isEmpty() && !lineCodes.isEmpty() && !mailCodes.equals(lineCodes)) {
                doubts.add(new Doubt("FOREIGN_DISAGREE", id, "mail " + mailCodes + " vs statement line " + lineCodes
                        + "; " + describe(t)));
                continue;
            }
            if (mailAmountDiffers) {
                doubts.add(new Doubt("FOREIGN_MAIL_AMOUNT_DIFFERS", id, "mail read in " + mailCodes
                        + " with an amount other than the stored one; " + describe(t)));
                continue;
            }
            String code = !mailCodes.isEmpty() ? mailCodes.iterator().next() : lineCodes.iterator().next();
            String via = !mailCodes.isEmpty() && !lineCodes.isEmpty() ? "mail+statementLine"
                    : !mailCodes.isEmpty() ? "mail" : (mailReadInr ? "statementLine (mail was read as INR)" : "statementLine");
            BigDecimal number = t.getAmount();
            if (out.size() < LIST_CAP) {
                out.add(new ForeignItem(id, str(t.getDate()), plain(number), text(t), code, plain(number), via));
            }
            t.setOriginalAmount(number);
            t.setOriginalCurrency(code);
            t.setAmount(null);
            transactionRepository.save(t);
            converted++;
        }
        return converted;
    }

    private record ForeignLine(UUID id, LocalDate date, TransactionType type, String cardLast4, Map<String, BigDecimal> printed) {
    }

    /** Each foreign ISO code a line prints right beside a decimal ("USD 20.00", "20.00 USD"), with that amount. */
    static Map<String, BigDecimal> printedForeignAmounts(String text) {
        if (text == null || text.isBlank()) return Map.of();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String token : text.split("[^A-Za-z]+")) {
            if (token.length() != 3) continue;
            String code = token.toUpperCase(Locale.ROOT);
            if (!FOREIGN_CODES.contains(code) || out.containsKey(code)) continue;
            BigDecimal amount = PairRules.printedForeignAmount(text, code);
            if (amount != null) out.put(code, amount);
        }
        return out;
    }

    /**
     * Step 2. The legacy reasons go from every row of the account; a NEEDS_REVIEW row left with none becomes
     * AUTO_REVIEWED. Any other review type (MANUALLY_REVIEWED included) is kept. Returns {rows, review type changed}.
     */
    private int[] clearLegacyReasons(UUID accountId, Map<String, Integer> byReason, List<ReviewChange> changes) {
        List<Transaction> rows = entityManager.createQuery(
                        "SELECT DISTINCT t FROM Transaction t JOIN t.reviewReasons r WHERE t.account.id = :a AND r IN :reasons",
                        Transaction.class)
                .setParameter("a", accountId)
                .setParameter("reasons", LEGACY_REASONS)
                .getResultList();
        rows.sort(Comparator.comparing(Transaction::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(t -> String.valueOf(t.getId())));
        int typeChanged = 0;
        for (Transaction t : rows) {
            List<String> removed = new ArrayList<>();
            for (ReviewReason r : LEGACY_REASONS) {
                if (t.getReviewReasons().remove(r)) {
                    removed.add(r.name());
                    byReason.merge(r.name(), 1, Integer::sum);
                }
            }
            ReviewType before = t.getReviewType();
            if (t.getReviewReasons().isEmpty() && before == ReviewType.NEEDS_REVIEW) {
                reviewStatusManager.transitionTo(t, ReviewType.AUTO_REVIEWED);
            }
            if (t.getReviewType() != before) {
                typeChanged++;
                if (changes.size() < LIST_CAP) {
                    changes.add(new ReviewChange(String.valueOf(t.getId()), str(t.getDate()), plain(t.getAmount()), text(t),
                            removed, before == null ? null : before.name(), t.getReviewType().name()));
                }
            }
        }
        if (!rows.isEmpty()) transactionRepository.saveAll(rows);
        return new int[]{rows.size(), typeChanged};
    }

    /**
     * Step 3. Every non-rejected statement of the account, oldest period first, re-matched as a statement move does:
     * its line-only rows are matched again by the new rules, and each one whose line now sits on another row is folded
     * into it. Returns {statements, merged, refused, tagged}.
     */
    private int[] rematchStatements(Account account, List<MergeItem> merges, List<RefusedMerge> refused, List<TagItem> tags) {
        List<Statement> statements = entityManager.createQuery(
                        "SELECT s FROM Statement s WHERE s.account.id = :a AND (s.verdict IS NULL OR s.verdict <> :rejected)",
                        Statement.class)
                .setParameter("a", account.getId())
                .setParameter("rejected", StatementVerdict.REJECTED)
                .getResultList();
        List<Statement> ordered = new ArrayList<>(statements);
        ordered.sort(Comparator.comparing(Statement::getPeriodStart, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Statement::getPeriodEnd, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Statement::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> String.valueOf(s.getId())));
        int merged = 0;
        int refusedCount = 0;
        int tagged = 0;
        for (Statement s : ordered) {
            StatementRef ref = new StatementRef(String.valueOf(s.getId()), str(s.getPeriodStart()), str(s.getPeriodEnd()),
                    s.getSource() == null ? null : s.getSource().name());
            Set<UUID> taggedBefore = new HashSet<>();
            transactionRepository.findByNotOnStatementId(s.getId()).forEach(t -> taggedBefore.add(t.getId()));

            StatementBatchMatcher.Result r = batchMatcher.rerun(s, account);

            Map<UUID, UUID> replaced = r.replacedOwnRows() == null ? Map.of() : r.replacedOwnRows();
            Map<UUID, Side[]> sides = new LinkedHashMap<>();
            Map<UUID, String> reasons = new HashMap<>();
            for (Map.Entry<UUID, UUID> e : replaced.entrySet()) {
                Transaction own = transactionRepository.findById(e.getKey()).orElse(null);
                Transaction keep = transactionRepository.findById(e.getValue()).orElse(null);
                if (own == null || keep == null) continue;
                StatementBatchMatcher.Prior prior = r.replacedTargetsBefore() == null ? null
                        : r.replacedTargetsBefore().get(keep.getId());
                sides.put(own.getId(), new Side[]{keepSide(keep, prior), side(own)});
                if (obligationRefService != null) {
                    String why = obligationRefService.checkMergeCompatibility(keep, own);
                    if (why != null) reasons.put(own.getId(), why);
                }
            }
            Set<UUID> folded = replaced.isEmpty() ? Set.of() : moveService.foldReplacedOwnRows(s.getId(), r);
            for (Map.Entry<UUID, Side[]> e : sides.entrySet()) {
                Side[] pair = e.getValue();
                if (folded.contains(e.getKey())) {
                    merged++;
                    if (merges.size() < LIST_CAP) merges.add(new MergeItem(pair[0], pair[1], ref));
                } else {
                    refusedCount++;
                    if (refused.size() < LIST_CAP) {
                        refused.add(new RefusedMerge(pair[0], pair[1], ref,
                                reasons.getOrDefault(e.getKey(), "merge refused (rows kept apart, line left on its own row)")));
                    }
                }
            }

            for (Transaction t : transactionRepository.findByNotOnStatementId(s.getId())) {
                if (taggedBefore.contains(t.getId())) continue;
                tagged++;
                if (tags.size() < LIST_CAP) {
                    tags.add(new TagItem(String.valueOf(t.getId()), str(t.getDate()), amountOrForeign(t), text(t), ref));
                }
            }
        }
        return new int[]{ordered.size(), merged, refusedCount, tagged};
    }

    /**
     * Step 4. Alert mails c094b3b skipped as covered by a statement go back to DISCOVERED with a clean slate, so the
     * normal drain matches them through {@link AlertMatcher} (attached, or created tagged). Returns {count, template read}.
     */
    private int[] requeueCoveredSkips(UUID accountId, List<RequeueItem> out) {
        List<GmailProcessedMessage> rows = entityManager.createQuery(
                        "SELECT g FROM GmailProcessedMessage g WHERE g.account.id = :a AND g.status = :skipped "
                                + "AND SUBSTRING(g.error, 1, 10) = 'Covered by' ORDER BY g.internalDate", GmailProcessedMessage.class)
                .setParameter("a", accountId)
                .setParameter("skipped", GmailProcessedStatus.SKIPPED_BEFORE_WATERMARK)
                .getResultList();
        int template = 0;
        for (GmailProcessedMessage g : rows) {
            // The pre-AI covered skip recorded no extract tier (a template read was its precondition); the post-AI one
            // recorded the engine's tier next to the AI read.
            boolean templateRead = g.getExtractTier() == null || "TEMPLATE".equals(g.getExtractTier());
            if (templateRead) template++;
            if (out.size() < REQUEUE_LIST_CAP) {
                out.add(new RequeueItem(String.valueOf(g.getId()), g.getSubject(),
                        g.getInternalDate() == null ? null : g.getInternalDate().atOffset(ZoneOffset.UTC).toLocalDate().toString(),
                        templateRead));
            }
            g.resetForRetry();
            g.setArchivedAt(null);
            g.setTransaction(null);
            g.setExtractMergeTxn(null);
        }
        return new int[]{rows.size(), template};
    }

    // ------------------------------------------------------------------ helpers

    private static Side side(Transaction t) {
        return new Side(String.valueOf(t.getId()), str(t.getDate()), amountOrForeign(t), text(t),
                t.getSource() == null ? null : t.getSource().name());
    }

    /** The kept row as it was before the line attached (date, bank text, a foreign charge's unknown amount). */
    private static Side keepSide(Transaction t, StatementBatchMatcher.Prior prior) {
        if (prior == null) return side(t);
        String amount = prior.amountWasNull() ? foreignOnly(t) : plain(t.getAmount());
        String text = prior.sourcedDescription() != null ? prior.sourcedDescription() : t.getDescription();
        return new Side(String.valueOf(t.getId()), str(prior.date()), amount, text,
                t.getSource() == null ? null : t.getSource().name());
    }

    private static String amountOrForeign(Transaction t) {
        return t.getAmount() != null ? plain(t.getAmount()) : foreignOnly(t);
    }

    private static String foreignOnly(Transaction t) {
        return t.getOriginalCurrency() == null ? null : t.getOriginalCurrency() + " " + plain(t.getOriginalAmount());
    }

    private static String describe(Transaction t) {
        return str(t.getDate()) + " " + plain(t.getAmount()) + " " + text(t);
    }

    private static String text(Transaction t) {
        return t.getSourcedDescription() != null ? t.getSourcedDescription() : t.getDescription();
    }

    private static String plain(BigDecimal b) {
        return b == null ? null : b.stripTrailingZeros().toPlainString();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String last4(Account a) {
        String l = a.primaryLast4();
        if (l == null && a.getBankDetails() != null) l = a.getBankDetails().getLast4();
        return l;
    }

    private static <T> List<T> cap(List<T> list, int max) {
        return list.size() <= max ? list : new ArrayList<>(list.subList(0, max));
    }
}
