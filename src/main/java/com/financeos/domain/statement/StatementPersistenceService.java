package com.financeos.domain.statement;

import com.financeos.domain.notification.StatementCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;

import com.financeos.domain.account.Account;
import com.financeos.domain.notification.bill.StatementDetailsRules;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.gmail.reconcile.ParsedStatementLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class StatementPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(StatementPersistenceService.class);

    private final StatementRepository statementRepository;
    private final StatementTransactionRepository statementTransactionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StatementPersistenceService(StatementRepository statementRepository,
            StatementTransactionRepository statementTransactionRepository,
            ApplicationEventPublisher eventPublisher) {
        this.statementRepository = statementRepository;
        this.statementTransactionRepository = statementTransactionRepository;
        this.eventPublisher = eventPublisher;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * True when a statement of the account already exists for the draft's period or for the file hash. Used after a
     * unique-key failure of {@link #createIfNew} (two uploads of the same statement racing past its existence
     * checks): the failed transaction is over by then, so this reads the winner's committed row. Not transactional.
     */
    public boolean alreadyExists(UUID accountId, StatementDraft draft, String fileSha256) {
        if (draft != null && draft.periodStart() != null && draft.periodEnd() != null
                && statementRepository.existsByAccountIdAndPeriodStartAndPeriodEnd(accountId,
                        draft.periodStart(), draft.periodEnd())) {
            return true;
        }
        return fileSha256 != null && statementRepository.existsByAccountIdAndFileSha256(accountId, fileSha256);
    }

    @Transactional
    public Optional<Statement> createIfNew(User user, Account account, StatementSource source, String sourceRef,
            String fileSha256, StatementDraft draft) {
        if (draft.periodStart() != null && draft.periodEnd() != null
                && statementRepository.existsByAccountIdAndPeriodStartAndPeriodEnd(account.getId(),
                        draft.periodStart(), draft.periodEnd())) {
            return Optional.empty();
        }
        if (fileSha256 != null && statementRepository.existsByAccountIdAndFileSha256(account.getId(), fileSha256)) {
            return Optional.empty();
        }

        Statement statement = new Statement();
        statement.setUser(user);
        statement.setAccount(account);
        statement.setSource(source);
        statement.setSourceRef(sourceRef);
        statement.setFileSha256(fileSha256);
        statement.setStatementType(draft.statementType());
        statement.setPeriodStart(draft.periodStart());
        statement.setPeriodEnd(draft.periodEnd());
        statement.setOpeningBalance(draft.openingBalance());
        statement.setClosingBalance(draft.closingBalance());
        statement.setBankName(draft.bankName());
        statement.setAccountNumberMasked(draft.accountNumberMasked());
        statement.setTransactionCount(draft.transactionCount());
        statement.setLinesSkipped(draft.linesSkipped());
        statement.setTotalDebits(draft.totalDebits());
        statement.setTotalCredits(draft.totalCredits());
        statement.setParseMode(draft.parseMode());
        statement.setChainValidationPct(draft.chainValidationPct());
        statement.setChecksumOk(draft.checksumOk());
        statement.setVerdict(draft.verdict());
        recordParse(statement, draft);

        Map<String, Object> cardFields = draft.cardFields();
        if ("credit_card".equals(draft.statementType()) && cardFields != null && !cardFields.isEmpty()) {
            StatementCreditCardDetails details = new StatementCreditCardDetails(statement);
            details.setUser(user);
            details.setTotalAmountDue((BigDecimal) cardFields.get("total_amount_due"));
            details.setMinimumAmountDue((BigDecimal) cardFields.get("minimum_amount_due"));
            details.setPaymentDueDate((LocalDate) cardFields.get("payment_due_date"));
            details.setCreditLimit((BigDecimal) cardFields.get("credit_limit"));
            details.setAvailableCreditLimit((BigDecimal) cardFields.get("available_credit_limit"));
            details.setFinanceCharges((BigDecimal) cardFields.get("finance_charges"));
            details.setFeesAndCharges((BigDecimal) cardFields.get("fees_and_charges"));
            details.setPreviousBalance((BigDecimal) cardFields.get("previous_balance"));
            details.setPaymentsReceived((BigDecimal) cardFields.get("payments_received"));
            details.setTotalPurchases((BigDecimal) cardFields.get("total_purchases"));
            details.setRewardPointsBalance((BigDecimal) cardFields.get("reward_points_balance"));
            details.setRewardPointsEarned((BigDecimal) cardFields.get("reward_points_earned"));
            statement.setCreditCardDetails(details);
        }

        Statement saved = statementRepository.save(statement);
        // Consumed after commit (bill digest push); nothing happens if the transaction rolls back.
        eventPublisher.publishEvent(new StatementCreatedEvent(saved.getId(), user.getId()));
        return Optional.of(saved);
    }

    /**
     * A re-upload of a card statement that already exists (same file, else same account and period
     * end), complete or not: corrects its period from the new parse and fills its empty figures.
     * The period is kept when the parsed one breaks {@link StatementDetailsRules} or another statement
     * of the account already ends on that date. A present figure is never overwritten and lines are
     * never touched. Empty when the draft is not a card statement or no live (non-rejected) statement
     * matches (the caller then takes the normal duplicate path); otherwise the fields changed
     * (empty list = nothing new in this file).
     */
    @Transactional
    public Optional<List<String>> refreshFromReupload(UUID accountId, String fileSha256, StatementDraft draft) {
        if (!"credit_card".equals(draft.statementType())) {
            return Optional.empty();
        }
        Statement target = findLiveTarget(accountId, fileSha256, draft);
        if (target == null) {
            return Optional.empty();
        }
        Map<String, Object> card = draft.cardFields() == null ? Map.of() : draft.cardFields();
        List<String> filled = new java.util.ArrayList<>();
        // A parsed period is trustworthy (label/range or labelled statement date), the stored one may have been guessed: replace it.
        boolean hadEnd = target.getPeriodEnd() != null;
        boolean periodChanged = false;
        // The whole period is replaced, so a guessed start never survives beside a parsed end.
        if (draft.periodEnd() != null && (!draft.periodEnd().equals(target.getPeriodEnd())
                || !java.util.Objects.equals(draft.periodStart(), target.getPeriodStart()))
                && periodAcceptable(target, accountId, draft)) {
            target.setPeriodStart(draft.periodStart());
            target.setPeriodEnd(draft.periodEnd());
            periodChanged = true;
        }
        if (periodChanged) {
            filled.add(hadEnd ? MissingDetails.PERIOD_UPDATED : MissingDetails.PERIOD_END);
        }
        LocalDate due = (LocalDate) card.get("payment_due_date");
        BigDecimal total = (BigDecimal) card.get("total_amount_due");
        BigDecimal min = (BigDecimal) card.get("minimum_amount_due");
        StatementCreditCardDetails d = target.getCreditCardDetails();
        boolean needsDetails = (due != null || total != null || min != null)
                && (d == null || d.getPaymentDueDate() == null || d.getTotalAmountDue() == null || d.getMinimumAmountDue() == null);
        if (needsDetails) {
            if (d == null) {
                d = new StatementCreditCardDetails(target);
                d.setUser(target.getUser());
                target.setCreditCardDetails(d);
            }
            if (d.getPaymentDueDate() == null && due != null) {
                d.setPaymentDueDate(due);
                filled.add(MissingDetails.DUE_DATE);
            }
            if (d.getTotalAmountDue() == null && total != null) {
                d.setTotalAmountDue(total);
                if (target.getClosingBalance() == null) {
                    target.setClosingBalance(total); // a card statement's closing balance is its total due
                }
                filled.add(MissingDetails.TOTAL_DUE);
            }
            if (d.getMinimumAmountDue() == null && min != null) {
                d.setMinimumAmountDue(min);
                filled.add(MissingDetails.MINIMUM_DUE);
            }
        }
        if (!filled.isEmpty()) {
            statementRepository.save(target);
        }
        return Optional.of(List.copyOf(filled));
    }

    private Statement findLiveTarget(UUID accountId, String fileSha256, StatementDraft draft) {
        Statement target = null;
        if (fileSha256 != null) {
            target = firstLive(statementRepository.findByAccountIdAndFileSha256(accountId, fileSha256));
        }
        if (target == null && draft.periodEnd() != null) {
            target = firstLive(statementRepository.findByAccountIdAndPeriodEnd(accountId, draft.periodEnd()));
        }
        return target;
    }

    /** Days a missing period start is taken to lie before its end, and how far apart two ends may be to be the same statement. */
    private static final int ASSUMED_PERIOD_DAYS = 45;
    private static final int END_TOLERANCE_DAYS = 10;

    /**
     * The statement (not in {@code skip}) a re-uploaded parse corrects when the file hash finds none: among the account's
     * live statements whose period overlaps the parsed one (a missing start is the end minus 45 days) or that end within
     * 10 days of it, the one with the same period when it has no links or 80% of its links pair exactly (date, |amount|,
     * direction) with a parsed line; else the one whose links pair 80% exactly and make up at least half the parsed lines
     * (a yearly never absorbs a monthly nor the reverse), the most exact pairs winning. A tie is ambiguous: none.
     * Neither: the only linkless statement ending on the same day (its start wrong or missing), else none.
     */
    private Statement findByPeriod(UUID accountId, StatementDraft draft, List<ParsedStatementLine> lines, Set<UUID> skip) {
        LocalDate end = draft.periodEnd();
        if (end == null) {
            return null;
        }
        LocalDate start = draft.periodStart() != null ? draft.periodStart() : end.minusDays(ASSUMED_PERIOD_DAYS);
        LocalDate from = start.isBefore(end.minusDays(END_TOLERANCE_DAYS)) ? start : end.minusDays(END_TOLERANCE_DAYS);
        List<Statement> candidates = new java.util.ArrayList<>();
        for (Statement c : statementRepository.findLiveByAccountIdEndingOnOrAfter(accountId, from)) {
            if (c.getPeriodEnd() == null || (skip != null && skip.contains(c.getId()))) continue;
            LocalDate cStart = c.getPeriodStart() != null ? c.getPeriodStart() : c.getPeriodEnd().minusDays(ASSUMED_PERIOD_DAYS);
            boolean overlaps = !cStart.isAfter(end) && !c.getPeriodEnd().isBefore(start);
            boolean nearEnd = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(c.getPeriodEnd(), end)) <= END_TOLERANCE_DAYS;
            if (overlaps || nearEnd) candidates.add(c);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Map<UUID, List<StatementTransactionRepository.StatementLineKey>> storedById = new java.util.HashMap<>();
        for (StatementTransactionRepository.StatementLineKey k : statementTransactionRepository.findLineKeysByStatementIdIn(
                candidates.stream().map(Statement::getId).toList())) {
            storedById.computeIfAbsent(k.getStatementId(), x -> new java.util.ArrayList<>()).add(k);
        }
        Map<String, Integer> parsedCounts = new java.util.HashMap<>();
        for (ParsedStatementLine l : lines) {
            parsedCounts.merge(lineKey(l.date(), l.amount(), TransactionType.fromLlmDirection(l.direction())), 1, Integer::sum);
        }
        Statement exactWinner = null;
        int exactWinnerPairs = -1;
        Statement best = null;
        int bestPairs = -1;
        boolean tie = false;
        List<Statement> emptySameEnd = new java.util.ArrayList<>();
        for (Statement c : candidates) {
            List<StatementTransactionRepository.StatementLineKey> stored = storedById.getOrDefault(c.getId(), List.of());
            Map<String, Integer> counts = new java.util.HashMap<>(parsedCounts);
            int pairs = 0;
            for (StatementTransactionRepository.StatementLineKey k : stored) {
                String key = lineKey(k.getDate(), k.getAmount(), k.getType());
                Integer n = counts.get(key);
                if (n != null && n > 0) {
                    counts.put(key, n - 1);
                    pairs++;
                }
            }
            boolean mostlyPaired = pairs * 5 >= stored.size() * 4;
            boolean samePeriod = end.equals(c.getPeriodEnd()) && java.util.Objects.equals(draft.periodStart(), c.getPeriodStart());
            if (!samePeriod && stored.isEmpty() && end.equals(c.getPeriodEnd())) emptySameEnd.add(c);
            if (samePeriod && (stored.isEmpty() || mostlyPaired)) {
                if (pairs > exactWinnerPairs) { // equal: the oldest stays
                    exactWinner = c;
                    exactWinnerPairs = pairs;
                }
            } else if (!stored.isEmpty() && mostlyPaired && pairs * 2 >= lines.size()) {
                if (pairs > bestPairs) {
                    best = c;
                    bestPairs = pairs;
                    tie = false;
                } else if (pairs == bestPairs) {
                    tie = true;
                }
            }
        }
        if (exactWinner != null) {
            return exactWinner;
        }
        if (tie) {
            log.info("Re-upload refresh skipped: parsed period {}..{} of account {} pairs equally with several statements",
                    draft.periodStart(), end, accountId);
            return null;
        }
        if (best != null) {
            return best;
        }
        // Last resort: the one linkless statement ending on the same day (its start wrong or missing).
        return emptySameEnd.size() == 1 ? emptySameEnd.get(0) : null;
    }

    /** The parse accounts for itself: AUTO_INGEST, and the card checks pass (card) or checksum holds with every row chain-valid (bank). */
    public static boolean fullyReconciles(StatementDraft draft) {
        if (draft == null || draft.verdict() != StatementVerdict.AUTO_INGEST) {
            return false;
        }
        if ("credit_card".equals(draft.statementType())) {
            return Boolean.TRUE.equals(draft.cardChecksOk());
        }
        return Boolean.TRUE.equals(draft.checksumOk()) && draft.chainValidationPct() != null
                && draft.chainValidationPct().compareTo(BigDecimal.valueOf(100)) == 0;
    }

    /**
     * What a true refresh did: the statement, the header fields that changed, the parsed lines (indexes into the list
     * given) no linked transaction accounts for, the linked transactions the parse does not have (reported, never
     * deleted), how many paired with an edited row, and how many existing links changed index, balance or chain flag ({@code relinked}).
     */
    public record Refreshed(Statement statement, List<String> changedFields, List<Integer> missingLines,
                            List<UUID> extraTransactionIds, int keptEdited, int relinked) {
        public Refreshed(Statement statement, List<String> changedFields, List<Integer> missingLines,
                         List<UUID> extraTransactionIds, int keptEdited) {
            this(statement, changedFields, missingLines, extraTransactionIds, keptEdited, 0);
        }

        public Refreshed(Statement statement, List<String> changedFields, List<Integer> missingLines,
                         List<UUID> extraTransactionIds) {
            this(statement, changedFields, missingLines, extraTransactionIds, 0, 0);
        }
    }

    /**
     * A re-upload whose new parse fully reconciles ({@link #fullyReconciles}) corrects the existing statement (same file,
     * else the one {@link #findByPeriod} picks, whose period is then rewritten from the parse or nothing happens when that
     * period breaks the rules or another statement already ends on it; not rejected, not in {@code skipStatementIds}): the header and card details are
     * overwritten from the parse (a null clears the field), id, source, file hash, account and user stay. The parsed
     * lines are compared with the linked transactions as a multiset of (date, amount, direction); a line with no
     * counterpart that pairs with an edited linked row (same direction; same amount within 5 days, or same date) keeps
     * that row. Every link then takes the index, balance and chain flag of its parsed line (linked rows the parse lacks
     * move after the parsed range). Unpaired lines are returned for the caller to match and create under the parsed
     * index; unpaired linked transactions are returned as extras. Transactions are never edited or deleted and no
     * StatementCreatedEvent is published. Run it in the transaction that creates the missing rows.
     * Empty when the parse does not fully reconcile or no such statement exists (the fill-blanks path then runs).
     */
    @Transactional
    public Optional<Refreshed> refreshReconciled(UUID accountId, String fileSha256, StatementDraft draft,
                                                 List<ParsedStatementLine> lines, Set<UUID> skipStatementIds) {
        if (!fullyReconciles(draft)) {
            return Optional.empty();
        }
        Statement target = fileSha256 == null ? null
                : firstLive(statementRepository.findByAccountIdAndFileSha256(accountId, fileSha256));
        boolean sameFile = target != null;
        if (target == null) {
            target = findByPeriod(accountId, draft, lines, skipStatementIds);
        }
        if (target == null || (skipStatementIds != null && skipStatementIds.contains(target.getId()))) {
            return Optional.empty();
        }
        boolean periodDiffers = draft.periodEnd() != null && (!draft.periodEnd().equals(target.getPeriodEnd())
                || !java.util.Objects.equals(draft.periodStart(), target.getPeriodStart()));
        boolean periodOk = periodDiffers && periodAcceptable(target, accountId, draft);
        // Found by period (not the same bytes) means the stored period is wrong: a parse that cannot replace it changes nothing.
        if (!sameFile && periodDiffers && !periodOk) {
            log.info("Re-upload refresh skipped: parsed period {}..{} cannot replace statement {}'s {}..{}",
                    draft.periodStart(), draft.periodEnd(), target.getId(), target.getPeriodStart(), target.getPeriodEnd());
            return Optional.empty();
        }
        List<String> changed = new java.util.ArrayList<>();
        if (periodOk) {
            target.setPeriodStart(draft.periodStart());
            target.setPeriodEnd(draft.periodEnd());
            changed.add("period");
        }
        if (differs(target.getOpeningBalance(), draft.openingBalance())) changed.add("opening_balance");
        target.setOpeningBalance(draft.openingBalance());
        if (differs(target.getClosingBalance(), draft.closingBalance())) changed.add("closing_balance");
        target.setClosingBalance(draft.closingBalance());
        if (differs(target.getTotalDebits(), draft.totalDebits())) changed.add("total_debits");
        target.setTotalDebits(draft.totalDebits());
        if (differs(target.getTotalCredits(), draft.totalCredits())) changed.add("total_credits");
        target.setTotalCredits(draft.totalCredits());
        if (!java.util.Objects.equals(target.getTransactionCount(), draft.transactionCount())) changed.add("transaction_count");
        target.setTransactionCount(draft.transactionCount());
        if (!java.util.Objects.equals(target.getLinesSkipped(), draft.linesSkipped())) changed.add("lines_skipped");
        target.setLinesSkipped(draft.linesSkipped());
        if (!java.util.Objects.equals(target.getParseMode(), draft.parseMode())) changed.add("parse_mode");
        target.setParseMode(draft.parseMode());
        if (differs(target.getChainValidationPct(), draft.chainValidationPct())) changed.add("chain_validation_pct");
        target.setChainValidationPct(draft.chainValidationPct());
        if (!java.util.Objects.equals(target.getChecksumOk(), draft.checksumOk())) changed.add("checksum");
        target.setChecksumOk(draft.checksumOk());
        if (target.getVerdict() != draft.verdict()) changed.add("verdict");
        target.setVerdict(draft.verdict());
        if ("credit_card".equals(draft.statementType())) {
            overwriteCardDetails(target, draft.cardFields() == null ? Map.of() : draft.cardFields(), changed);
        }
        // The new parse is applied here (header and card figures overwritten, links re-indexed below), so the statement's version is replaced. The snapshot itself is written by the caller after commit (StatementParseRecords#saveQuietly).
        boolean recorded = recordParse(target, draft);
        if (!changed.isEmpty() || recorded) {
            statementRepository.save(target);
        }

        // Lines: multiset (date, amount, direction) of the parse against the linked transactions.
        Map<String, java.util.ArrayDeque<Integer>> parsedByKey = new java.util.HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ParsedStatementLine l = lines.get(i);
            parsedByKey.computeIfAbsent(lineKey(l.date(), l.amount(), TransactionType.fromLlmDirection(l.direction())),
                    k -> new java.util.ArrayDeque<>()).add(i);
        }
        Map<UUID, Integer> indexOfTxn = new java.util.HashMap<>();
        List<StatementLineProjection> extraRows = new java.util.ArrayList<>();
        for (StatementLineProjection p : statementTransactionRepository.findLinesByStatementId(target.getId())) {
            java.util.ArrayDeque<Integer> q = parsedByKey.get(lineKey(p.date(), p.amount(), p.type()));
            if (q != null && !q.isEmpty()) {
                indexOfTxn.put(p.transactionId(), q.poll());
            } else {
                extraRows.add(p);
            }
        }
        List<Integer> missing = new java.util.ArrayList<>();
        parsedByKey.values().forEach(missing::addAll);
        java.util.Collections.sort(missing);
        // A missing line that is probably an extra row the user edited (same direction; same amount within 5 days, or same
        // date with another amount) is that row: neither added nor reported as extra.
        record Pair(int line, int extra, int rank, long days) {
        }
        List<Pair> candidates = new java.util.ArrayList<>();
        for (int mi = 0; mi < missing.size(); mi++) {
            ParsedStatementLine l = lines.get(missing.get(mi));
            TransactionType dir = TransactionType.fromLlmDirection(l.direction());
            for (int ei = 0; ei < extraRows.size(); ei++) {
                StatementLineProjection e = extraRows.get(ei);
                if (e.date() == null || l.date() == null || e.type() != dir) continue;
                long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(l.date(), e.date()));
                boolean sameAmount = e.amount() != null && l.amount() != null && e.amount().abs().compareTo(l.amount().abs()) == 0;
                if ((sameAmount && days <= 5) || days == 0) {
                    candidates.add(new Pair(mi, ei, sameAmount ? 0 : 1, days));
                }
            }
        }
        candidates.sort(java.util.Comparator.comparingInt(Pair::rank).thenComparingLong(Pair::days)
                .thenComparingInt(Pair::line).thenComparingInt(Pair::extra));
        java.util.Set<Integer> usedLines = new java.util.HashSet<>();
        java.util.Set<Integer> usedExtras = new java.util.HashSet<>();
        for (Pair c : candidates) {
            if (usedLines.contains(c.line()) || usedExtras.contains(c.extra())) continue;
            usedLines.add(c.line());
            usedExtras.add(c.extra());
            indexOfTxn.put(extraRows.get(c.extra()).transactionId(), missing.get(c.line()));
        }
        List<Integer> toAdd = new java.util.ArrayList<>();
        for (int mi = 0; mi < missing.size(); mi++) {
            if (!usedLines.contains(mi)) toAdd.add(missing.get(mi));
        }
        List<UUID> extra = new java.util.ArrayList<>();
        for (int ei = 0; ei < extraRows.size(); ei++) {
            if (!usedExtras.contains(ei)) extra.add(extraRows.get(ei).transactionId());
        }
        // Link columns follow the parse; extras move behind the parsed range so nothing collides. Transactions stay as they are.
        List<StatementTransaction> reindexed = new java.util.ArrayList<>();
        List<StatementTransaction> extraLinks = new java.util.ArrayList<>();
        for (StatementTransaction st : statementTransactionRepository.findByIdStatementId(target.getId())) {
            Integer idx = indexOfTxn.get(st.getId().getTransactionId());
            if (idx == null) {
                extraLinks.add(st);
                continue;
            }
            ParsedStatementLine l = lines.get(idx);
            if (!idx.equals(st.getLineIndex()) || differs(st.getBalanceAfter(), l.balance())
                    || !java.util.Objects.equals(st.getChainValid(), l.chainValid())) {
                st.setLineIndex(idx);
                st.setBalanceAfter(l.balance());
                st.setChainValid(l.chainValid());
                reindexed.add(st);
            }
        }
        // Extras are numbered behind the parsed range by their current order, so a repeat upload changes nothing.
        extraLinks.sort(java.util.Comparator.comparing(StatementTransaction::getLineIndex,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        int rank = 0;
        for (StatementTransaction st : extraLinks) {
            if (st.getLineIndex() == null) continue;
            Integer want = lines.size() + rank++;
            if (!want.equals(st.getLineIndex())) {
                st.setLineIndex(want);
                reindexed.add(st);
            }
        }
        if (!reindexed.isEmpty()) {
            statementTransactionRepository.saveAll(reindexed);
        }
        return Optional.of(new Refreshed(target, List.copyOf(changed), List.copyOf(toAdd), List.copyOf(extra), usedLines.size(), reindexed.size()));
    }

    /**
     * Stamps the statement with the parser version of the draft and when it was recorded; false when the draft carries
     * no parser version (not from the parser), in which case the stored record is left as it is.
     */
    private static boolean recordParse(Statement statement, StatementDraft draft) {
        if (draft.parserVersion() == null) {
            return false;
        }
        statement.setParserVersion(draft.parserVersion());
        statement.setParseRecordedAt(java.time.Instant.now());
        return true;
    }

    private static String lineKey(LocalDate date, BigDecimal amount, TransactionType type) {
        return date + "|" + (amount == null ? "?" : amount.abs().stripTrailingZeros().toPlainString()) + "|" + type;
    }

    private static boolean differs(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? a != b : a.compareTo(b) != 0;
    }

    /** Every parsed card figure replaces the stored one (null clears it); the bill-payment tracking fields stay. */
    private void overwriteCardDetails(Statement target, Map<String, Object> card, List<String> changed) {
        StatementCreditCardDetails d = target.getCreditCardDetails();
        if (d == null) {
            d = new StatementCreditCardDetails(target);
            d.setUser(target.getUser());
            target.setCreditCardDetails(d);
        }
        d.setTotalAmountDue(card(card, "total_amount_due", d.getTotalAmountDue(), changed));
        d.setMinimumAmountDue(card(card, "minimum_amount_due", d.getMinimumAmountDue(), changed));
        LocalDate due = (LocalDate) card.get("payment_due_date");
        if (!java.util.Objects.equals(due, d.getPaymentDueDate())) changed.add("payment_due_date");
        d.setPaymentDueDate(due);
        d.setCreditLimit(card(card, "credit_limit", d.getCreditLimit(), changed));
        d.setAvailableCreditLimit(card(card, "available_credit_limit", d.getAvailableCreditLimit(), changed));
        d.setFinanceCharges(card(card, "finance_charges", d.getFinanceCharges(), changed));
        d.setFeesAndCharges(card(card, "fees_and_charges", d.getFeesAndCharges(), changed));
        d.setPreviousBalance(card(card, "previous_balance", d.getPreviousBalance(), changed));
        d.setPaymentsReceived(card(card, "payments_received", d.getPaymentsReceived(), changed));
        d.setTotalPurchases(card(card, "total_purchases", d.getTotalPurchases(), changed));
        d.setRewardPointsBalance(card(card, "reward_points_balance", d.getRewardPointsBalance(), changed));
        d.setRewardPointsEarned(card(card, "reward_points_earned", d.getRewardPointsEarned(), changed));
    }

    /** The parsed value of one card figure; records the field when it differs from the stored one. */
    private static BigDecimal card(Map<String, Object> card, String key, BigDecimal stored, List<String> changed) {
        BigDecimal parsed = (BigDecimal) card.get(key);
        if (differs(stored, parsed)) changed.add(key);
        return parsed;
    }

    private static Statement firstLive(List<Statement> candidates) {
        for (Statement s : candidates) {
            if (s.getVerdict() != StatementVerdict.REJECTED) {
                return s;
            }
        }
        return null;
    }

    /** The parsed period passes the shared rules and no other statement of the account already ends on its end date. */
    private boolean periodAcceptable(Statement target, UUID accountId, StatementDraft draft) {
        try {
            StatementDetailsRules.validateRelations(draft.periodStart(), draft.periodEnd(), null, null, null,
                    draft.periodStart(), draft.periodEnd(), null, null, null);
        } catch (RuntimeException e) {
            return false;
        }
        if (draft.periodEnd().equals(target.getPeriodEnd())) {
            return true;
        }
        return statementRepository.findByAccountIdAndPeriodEnd(accountId, draft.periodEnd()).stream()
                .noneMatch(o -> !o.getId().equals(target.getId()));
    }

    public record TxnLink(UUID transactionId, int lineIndex, BigDecimal balanceAfter, Boolean chainValid) {
    }

    /** Ids of the account's transactions in the date range that some statement already links to. */
    @Transactional(readOnly = true)
    public Set<UUID> findStatementLinkedTransactionIds(UUID accountId, LocalDate startDate, LocalDate endDate) {
        return statementTransactionRepository.findLinkedTransactionIds(accountId, startDate, endDate);
    }

    @Transactional
    public void linkTransactions(UUID statementId, List<TxnLink> links) {
        Statement statement = statementRepository.getReferenceById(statementId);
        for (TxnLink link : links) {
            StatementTransaction st = new StatementTransaction(
                    statement.getId(),
                    link.transactionId(),
                    link.lineIndex(),
                    link.balanceAfter(),
                    link.chainValid()
            );
            statementTransactionRepository.save(st);
        }
    }
}
