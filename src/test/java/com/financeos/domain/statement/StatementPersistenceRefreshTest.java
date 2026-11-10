package com.financeos.domain.statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** A re-upload whose new parse fully reconciles corrects the stored statement; anything less keeps fill-blanks-only. */
class StatementPersistenceRefreshTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private final StatementRepository repository = mock(StatementRepository.class);
    private final StatementTransactionRepository links = mock(StatementTransactionRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final StatementPersistenceService service = new StatementPersistenceService(repository, links, events);
    private final UUID accountId = UUID.randomUUID();

    private Optional<StatementPersistenceService.Refreshed> refresh(UUID account, String sha, StatementDraft d, List<ParsedStatementLine> lines) {
        return service.refreshReconciled(account, sha, d, lines, java.util.Set.of());
    }

    private Statement card(BigDecimal total, LocalDate due) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(new User());
        s.setStatementType("credit_card");
        s.setVerdict(StatementVerdict.NEEDS_REVIEW);
        s.setPeriodStart(START);
        s.setPeriodEnd(END);
        s.setCreatedAt(Instant.now());
        s.setClosingBalance(total);
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setTotalAmountDue(total);
        d.setMinimumAmountDue(new BigDecimal("100"));
        d.setPaymentDueDate(due);
        d.setPreviousBalance(new BigDecimal("-50"));
        d.setPaidMarkedOn(LocalDate.of(2026, 10, 1));
        s.setCreditCardDetails(d);
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));
        return s;
    }

    private StatementDraft cardDraft(Boolean cardOk, StatementVerdict verdict, BigDecimal total, LocalDate due, BigDecimal prev) {
        Map<String, Object> f = new HashMap<>();
        f.put("total_amount_due", total);
        f.put("previous_balance", prev);
        if (due != null) f.put("payment_due_date", due);
        return new StatementDraft("credit_card", START, END, prev, total, "HDFC", "1234", 2, 0, null, null, "heuristic",
                new BigDecimal("100"), null, verdict, f, cardOk);
    }

    private static ParsedStatementLine line(int day, String amount, String dir) {
        return new ParsedStatementLine(LocalDate.of(2026, 9, day), new BigDecimal(amount), dir, "x", null, null);
    }

    private static StatementLineProjection linked(UUID txn, int index, int day, String amount, TransactionType type) {
        return new StatementLineProjection(txn, index, LocalDate.of(2026, 9, day), "x", new BigDecimal(amount), type,
                null, null, null, null, null);
    }

    @Test
    void reconciledCardReuploadOverwritesHeaderAndClearsAnInventedDueDate() {
        Statement s = card(new BigDecimal("-900"), LocalDate.of(2026, 10, 18));
        StatementDraft d = cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, new BigDecimal("0"));

        StatementPersistenceService.Refreshed r = refresh(accountId, "sha", d, List.of()).orElseThrow();

        StatementCreditCardDetails c = s.getCreditCardDetails();
        assertEquals(0, new BigDecimal("900").compareTo(c.getTotalAmountDue()), "wrong sign corrected");
        assertEquals(0, BigDecimal.ZERO.compareTo(c.getPreviousBalance()));
        assertNull(c.getPaymentDueDate(), "the parse has no due date: cleared");
        assertNull(c.getMinimumAmountDue());
        assertEquals(0, new BigDecimal("900").compareTo(s.getClosingBalance()));
        assertEquals(StatementVerdict.AUTO_INGEST, s.getVerdict());
        assertEquals(LocalDate.of(2026, 10, 1), c.getPaidMarkedOn(), "bill-payment tracking stays");
        assertTrue(r.changedFields().containsAll(List.of("total_amount_due", "payment_due_date", "previous_balance", "verdict")));
        verify(repository).save(s);
        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addsOnlyTheMissingLinesAndReportsExtrasWithoutDeletingAnything() {
        Statement s = card(new BigDecimal("900"), null);
        UUID kept = UUID.randomUUID();
        UUID extra = UUID.randomUUID();
        when(links.findLinesByStatementId(s.getId())).thenReturn(List.of(
                linked(kept, 4, 5, "100", TransactionType.DEBIT),
                linked(extra, 7, 9, "55", TransactionType.DEBIT)));
        StatementDraft d = cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, new BigDecimal("0"));

        StatementPersistenceService.Refreshed r = refresh(accountId, "sha", d,
                List.of(line(5, "100", "DEBIT"), line(6, "30", "DEBIT"), line(6, "30", "CREDIT"))).orElseThrow();

        assertEquals(List.of(1, 2), r.missingLines(), "same amount other direction is a different line");
        assertEquals(List.of(extra), r.extraTransactionIds());
        verify(links, never()).deleteByStatementId(org.mockito.ArgumentMatchers.any());
        verify(links, never()).deleteAll(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void identicalLinesAreCountedAsAMultiset() {
        Statement s = card(new BigDecimal("900"), null);
        when(links.findLinesByStatementId(s.getId())).thenReturn(List.of(linked(UUID.randomUUID(), 0, 5, "100", TransactionType.DEBIT)));
        StatementDraft d = cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, new BigDecimal("0"));

        StatementPersistenceService.Refreshed r = refresh(accountId, "sha", d,
                List.of(line(5, "100", "DEBIT"), line(5, "100", "DEBIT"))).orElseThrow();

        assertEquals(List.of(1), r.missingLines());
        assertTrue(r.extraTransactionIds().isEmpty());
    }

    @Test
    void aParseThatDoesNotFullyReconcileTakesNoPartInTheRefresh() {
        card(new BigDecimal("900"), null);
        assertTrue(refresh(accountId, "sha",
                cardDraft(false, StatementVerdict.AUTO_INGEST, new BigDecimal("1"), null, BigDecimal.ZERO), List.of()).isEmpty());
        assertTrue(refresh(accountId, "sha",
                cardDraft(true, StatementVerdict.NEEDS_REVIEW, new BigDecimal("1"), null, BigDecimal.ZERO), List.of()).isEmpty());
        assertTrue(refresh(accountId, "sha",
                cardDraft(null, StatementVerdict.AUTO_INGEST, new BigDecimal("1"), null, BigDecimal.ZERO), List.of()).isEmpty());
        // and the fill-blanks path is untouched: a present total is never overwritten
        Optional<List<String>> filled = service.refreshFromReupload(accountId, "sha",
                cardDraft(false, StatementVerdict.AUTO_INGEST, new BigDecimal("1"), LocalDate.of(2026, 10, 20), BigDecimal.ZERO));
        assertEquals(List.of("DUE_DATE"), filled.orElseThrow());
    }

    @Test
    void noLiveStatementMeansNoRefresh() {
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of());
        when(repository.findByAccountIdAndPeriodEnd(accountId, END)).thenReturn(List.of());
        assertTrue(refresh(accountId, "sha",
                cardDraft(true, StatementVerdict.AUTO_INGEST, BigDecimal.ONE, null, BigDecimal.ZERO), List.of()).isEmpty());
    }

    @Test
    void reconciledBankReuploadOverwritesBalancesChainAndChecksum() {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(new User());
        s.setStatementType("bank_account");
        s.setVerdict(StatementVerdict.NEEDS_REVIEW);
        s.setPeriodStart(START);
        s.setPeriodEnd(END);
        s.setOpeningBalance(new BigDecimal("10"));
        s.setClosingBalance(new BigDecimal("20"));
        s.setChecksumOk(false);
        s.setChainValidationPct(new BigDecimal("80"));
        s.setCreatedAt(Instant.now());
        when(repository.findByAccountIdAndFileSha256(accountId, "sha")).thenReturn(List.of(s));
        StatementDraft d = new StatementDraft("bank_account", START, END, new BigDecimal("1000"), new BigDecimal("1500"), "HDFC",
                "1234", 3, 0, new BigDecimal("200"), new BigDecimal("700"), "balance_chain", new BigDecimal("100"), true,
                StatementVerdict.AUTO_INGEST, null);

        StatementPersistenceService.Refreshed r = refresh(accountId, "sha", d, List.of()).orElseThrow();

        assertEquals(0, new BigDecimal("1000").compareTo(s.getOpeningBalance()));
        assertEquals(0, new BigDecimal("1500").compareTo(s.getClosingBalance()));
        assertEquals(Boolean.TRUE, s.getChecksumOk());
        assertEquals(0, new BigDecimal("100").compareTo(s.getChainValidationPct()));
        assertEquals("balance_chain", s.getParseMode());
        assertEquals(StatementVerdict.AUTO_INGEST, s.getVerdict());
        assertNull(s.getCreditCardDetails());
        assertTrue(r.changedFields().containsAll(List.of("opening_balance", "closing_balance", "checksum", "chain_validation_pct")));
    }

    @Test
    void aBankParseBelowFullChainOrWithoutChecksumDoesNotCount() {
        StatementDraft base = new StatementDraft("bank_account", START, END, null, null, null, null, 1, 0, null, null, "balance_chain",
                new BigDecimal("99.9"), true, StatementVerdict.AUTO_INGEST, null);
        assertFalse(StatementPersistenceService.fullyReconciles(base));
        assertFalse(StatementPersistenceService.fullyReconciles(new StatementDraft("bank_account", START, END, null, null, null, null, 1, 0,
                null, null, "balance_chain", new BigDecimal("100"), null, StatementVerdict.AUTO_INGEST, null)));
        assertTrue(StatementPersistenceService.fullyReconciles(new StatementDraft("bank_account", START, END, null, null, null, null, 1, 0,
                null, null, "balance_chain", new BigDecimal("100.0"), true, StatementVerdict.AUTO_INGEST, null)));
    }

    @Test
    void anEditedAmountOnTheSameDateIsKeptNotAddedNorExtra() {
        Statement s = card(new BigDecimal("900"), null);
        UUID edited = UUID.randomUUID();
        when(links.findLinesByStatementId(s.getId())).thenReturn(List.of(linked(edited, 0, 5, "120", TransactionType.DEBIT)));
        StatementDraft d = cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, BigDecimal.ZERO);

        StatementPersistenceService.Refreshed r = refresh(accountId, "sha", d, List.of(line(5, "100", "DEBIT"))).orElseThrow();

        assertTrue(r.missingLines().isEmpty());
        assertTrue(r.extraTransactionIds().isEmpty());
        assertEquals(1, r.keptEdited());
    }

    @Test
    void anEditedDateWithinFiveDaysAtTheSameAmountIsKeptButSixDaysIsNot() {
        Statement s = card(new BigDecimal("900"), null);
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        when(links.findLinesByStatementId(s.getId())).thenReturn(List.of(
                linked(near, 0, 10, "100", TransactionType.DEBIT), linked(far, 1, 26, "70", TransactionType.DEBIT)));
        StatementDraft d = cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, BigDecimal.ZERO);

        StatementPersistenceService.Refreshed r = refresh(accountId, "sha", d,
                List.of(line(5, "100", "DEBIT"), line(20, "70", "DEBIT"))).orElseThrow();

        assertEquals(List.of(1), r.missingLines(), "26th vs 20th is 6 days: a different row");
        assertEquals(List.of(far), r.extraTransactionIds());
        assertEquals(1, r.keptEdited());
    }

    @Test
    void pairingIsOneToOneAndPrefersTheExactAmount() {
        Statement s = card(new BigDecimal("900"), null);
        UUID exact = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(links.findLinesByStatementId(s.getId())).thenReturn(List.of(
                linked(other, 0, 5, "999", TransactionType.DEBIT), linked(exact, 1, 8, "100", TransactionType.DEBIT)));
        StatementDraft d = cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, BigDecimal.ZERO);

        StatementPersistenceService.Refreshed r = refresh(accountId, "sha", d,
                List.of(line(5, "100", "DEBIT"), line(5, "100", "CREDIT"))).orElseThrow();

        assertEquals(1, r.keptEdited());
        assertEquals(List.of(1), r.missingLines(), "the credit has no debit to pair with");
        assertEquals(List.of(other), r.extraTransactionIds(), "the exact-amount row took the line, not the same-date one");
    }

    @Test
    void linksTakeTheirParsedLineIndexBalanceAndChainFlagAndExtrasMoveBehindTheRange() {
        Statement s = card(new BigDecimal("900"), null);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID edited = UUID.randomUUID();
        UUID extra = UUID.randomUUID();
        when(links.findLinesByStatementId(s.getId())).thenReturn(List.of(
                linked(b, 0, 7, "70", TransactionType.DEBIT), linked(a, 1, 5, "50", TransactionType.DEBIT),
                linked(edited, 2, 9, "999", TransactionType.DEBIT), linked(extra, 3, 20, "5", TransactionType.CREDIT)));
        StatementTransaction la = new StatementTransaction(s.getId(), a, 1, null, null);
        StatementTransaction lb = new StatementTransaction(s.getId(), b, 0, null, null);
        StatementTransaction le = new StatementTransaction(s.getId(), edited, 2, null, null);
        StatementTransaction lx = new StatementTransaction(s.getId(), extra, 3, null, null);
        when(links.findByIdStatementId(s.getId())).thenReturn(List.of(la, lb, le, lx));
        List<ParsedStatementLine> parsed = List.of(
                new ParsedStatementLine(LocalDate.of(2026, 9, 5), new BigDecimal("50"), "DEBIT", "x", new BigDecimal("950"), true),
                new ParsedStatementLine(LocalDate.of(2026, 9, 7), new BigDecimal("70"), "DEBIT", "x", new BigDecimal("880"), true),
                new ParsedStatementLine(LocalDate.of(2026, 9, 9), new BigDecimal("100"), "DEBIT", "x", new BigDecimal("780"), false),
                new ParsedStatementLine(LocalDate.of(2026, 9, 12), new BigDecimal("10"), "DEBIT", "x", new BigDecimal("770"), true));

        StatementPersistenceService.Refreshed r = refresh(accountId, "sha",
                cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, BigDecimal.ZERO), parsed).orElseThrow();

        assertEquals(0, la.getLineIndex());
        assertEquals(0, new BigDecimal("950").compareTo(la.getBalanceAfter()));
        assertEquals(1, lb.getLineIndex());
        assertEquals(2, le.getLineIndex(), "the edited row sits where its parsed line is");
        assertEquals(Boolean.FALSE, le.getChainValid());
        assertEquals(4, lx.getLineIndex(), "extras move behind the parsed range");
        assertEquals(List.of(3), r.missingLines(), "the new row keeps its parsed index");
        assertEquals(List.of(extra), r.extraTransactionIds());
        verify(links).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void aStatementInTheSkipSetIsNotRefreshed() {
        card(new BigDecimal("900"), null);
        UUID id = repository.findByAccountIdAndFileSha256(accountId, "sha").get(0).getId();
        assertTrue(service.refreshReconciled(accountId, "sha",
                cardDraft(true, StatementVerdict.AUTO_INGEST, BigDecimal.ONE, null, BigDecimal.ZERO), List.of(),
                java.util.Set.of(id)).isEmpty());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aSecondIdenticalReuploadChangesNothing() {
        Statement s = card(new BigDecimal("900"), null);
        UUID a = UUID.randomUUID();
        UUID extra = UUID.randomUUID();
        when(links.findLinesByStatementId(s.getId())).thenReturn(List.of(
                linked(a, 0, 5, "50", TransactionType.DEBIT), linked(extra, 1, 20, "5", TransactionType.CREDIT)));
        StatementTransaction la = new StatementTransaction(s.getId(), a, 0, new BigDecimal("950"), true);
        StatementTransaction lx = new StatementTransaction(s.getId(), extra, 1, null, null);
        when(links.findByIdStatementId(s.getId())).thenReturn(List.of(la, lx));
        List<ParsedStatementLine> parsed = List.of(
                new ParsedStatementLine(LocalDate.of(2026, 9, 5), new BigDecimal("50"), "DEBIT", "x", new BigDecimal("950"), true));
        StatementDraft d = cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, BigDecimal.ZERO);

        refresh(accountId, "sha", d, parsed);
        assertEquals(1, lx.getLineIndex(), "extra renumbered to lines.size() + rank");
        org.mockito.Mockito.clearInvocations(links, repository);
        StatementPersistenceService.Refreshed again = refresh(accountId, "sha", d, parsed).orElseThrow();

        assertTrue(again.changedFields().isEmpty());
        assertTrue(again.missingLines().isEmpty());
        assertEquals(1, lx.getLineIndex());
        verify(links, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private List<StatementLineProjection> fiveStored() {
        return List.of(linked(UUID.randomUUID(), 0, 1, "10", TransactionType.DEBIT),
                linked(UUID.randomUUID(), 1, 2, "20", TransactionType.DEBIT),
                linked(UUID.randomUUID(), 2, 3, "30", TransactionType.DEBIT),
                linked(UUID.randomUUID(), 3, 4, "40", TransactionType.DEBIT),
                linked(UUID.randomUUID(), 4, 5, "50", TransactionType.DEBIT));
    }

    private Statement foundOnlyByPeriod(List<StatementLineProjection> stored) {
        Statement s = card(new BigDecimal("900"), null);
        when(repository.findByAccountIdAndFileSha256(accountId, "other")).thenReturn(List.of());
        when(repository.findByAccountIdAndPeriodEnd(accountId, END)).thenReturn(List.of(s));
        when(repository.findLiveByAccountIdEndingOnOrAfter(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(s));
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(keys(s, stored));
        when(links.findLinesByStatementId(s.getId())).thenReturn(stored);
        return s;
    }

    private static List<StatementTransactionRepository.StatementLineKey> keys(Statement s, List<StatementLineProjection> stored) {
        return stored.stream().map(p -> key(s.getId(), p.date(), p.amount(), p.type())).toList();
    }

    private static StatementTransactionRepository.StatementLineKey key(UUID statementId, LocalDate date, BigDecimal amount,
                                                                       TransactionType type) {
        return new StatementTransactionRepository.StatementLineKey() {
            public UUID getStatementId() { return statementId; }
            public LocalDate getDate() { return date; }
            public BigDecimal getAmount() { return amount; }
            public TransactionType getType() { return type; }
        };
    }

    private StatementDraft okDraft() {
        return cardDraft(true, StatementVerdict.AUTO_INGEST, new BigDecimal("900"), null, new BigDecimal("0"));
    }

    @Test
    void sameFileRefreshesEvenWhenNoLineOverlaps() {
        Statement s = card(new BigDecimal("900"), null);
        when(links.findLinesByStatementId(s.getId())).thenReturn(fiveStored());
        assertTrue(refresh(accountId, "sha", okDraft(), List.of(line(20, "7", "DEBIT"), line(21, "8", "DEBIT"))).isPresent());
    }

    @Test
    void periodMatchWithUnderEightyPercentExactIsADifferentDocumentAndWritesNothing() {
        foundOnlyByPeriod(fiveStored());
        // 3 of 5 pair exactly (60%).
        assertTrue(refresh(accountId, "other", okDraft(), List.of(line(1, "10", "DEBIT"), line(2, "20", "DEBIT"),
                line(3, "30", "DEBIT"), line(9, "99", "DEBIT"), line(10, "98", "DEBIT"))).isEmpty());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(links, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void periodMatchWithEightyPercentExactRefreshes() {
        foundOnlyByPeriod(fiveStored());
        StatementPersistenceService.Refreshed r = refresh(accountId, "other", okDraft(), List.of(line(1, "10", "DEBIT"),
                line(2, "20", "DEBIT"), line(3, "30", "DEBIT"), line(4, "40", "DEBIT"), line(9, "99", "DEBIT"))).orElseThrow();
        assertEquals(List.of(4), r.missingLines());
    }

    @Test
    void periodMatchWithNoStoredLinksRefreshes() {
        foundOnlyByPeriod(List.of());
        assertTrue(refresh(accountId, "other", okDraft(), List.of(line(9, "99", "DEBIT"))).isPresent());
    }

    // --- Lookup when the file hash misses: overlapping / near-end candidates, scored by exact line pairs. ---

    private Statement stored(LocalDate start, LocalDate end) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(new User());
        s.setStatementType("bank_account");
        s.setVerdict(StatementVerdict.NEEDS_REVIEW);
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setCreatedAt(Instant.now());
        return s;
    }

    private StatementDraft bankDraft(LocalDate start, LocalDate end, int count) {
        return new StatementDraft("bank_account", start, end, new BigDecimal("10"), new BigDecimal("20"), "HDFC", "1234", count, 0,
                null, null, "balance_chain", new BigDecimal("100"), true, StatementVerdict.AUTO_INGEST, null);
    }

    private static ParsedStatementLine on(LocalDate date, String amount) {
        return new ParsedStatementLine(date, new BigDecimal(amount), "DEBIT", "x", null, null);
    }

    private static List<ParsedStatementLine> daily(LocalDate from, int n) {
        List<ParsedStatementLine> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) out.add(on(from.plusDays(i), String.valueOf(10 + i)));
        return out;
    }

    private static List<StatementTransactionRepository.StatementLineKey> keysOf(Statement s, List<ParsedStatementLine> lines) {
        return lines.stream().map(l -> key(s.getId(), l.date(), l.amount(), TransactionType.DEBIT)).toList();
    }

    private void candidates(Statement... ss) {
        when(repository.findByAccountIdAndFileSha256(accountId, "new")).thenReturn(List.of());
        when(repository.findLiveByAccountIdEndingOnOrAfter(org.mockito.ArgumentMatchers.eq(accountId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(ss));
    }

    @Test
    void aMonthlyWithTheExactPeriodAndNoLinksBeatsAYearlyEndingTheSameDay() {
        LocalDate mStart = LocalDate.of(2026, 3, 1);
        LocalDate mEnd = LocalDate.of(2026, 3, 31);
        Statement yearly = stored(LocalDate.of(2025, 4, 1), mEnd);
        Statement monthly = stored(mStart, mEnd);
        candidates(yearly, monthly);
        List<ParsedStatementLine> parsed = daily(LocalDate.of(2026, 3, 2), 20);
        List<StatementTransactionRepository.StatementLineKey> yearlyLinks = new java.util.ArrayList<>(keysOf(yearly, parsed));
        yearlyLinks.addAll(keysOf(yearly, daily(LocalDate.of(2025, 5, 1), 250)));
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(yearlyLinks);

        StatementPersistenceService.Refreshed r = refresh(accountId, "new", bankDraft(mStart, mEnd, 20), parsed).orElseThrow();

        assertEquals(monthly, r.statement());
        assertEquals(20, r.missingLines().size(), "the monthly had no links: every line is missing");
        assertFalse(r.changedFields().contains("period"));
        verify(links).findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 2));
    }

    @Test
    void aStoredEndOffByTwoDaysWithMostLinesPairedIsRefreshedAndItsPeriodRewritten() {
        LocalDate pStart = LocalDate.of(2022, 5, 18);
        LocalDate pEnd = LocalDate.of(2022, 6, 17);
        Statement s = stored(LocalDate.of(2022, 5, 24), LocalDate.of(2022, 6, 15));
        candidates(s);
        List<ParsedStatementLine> parsed = daily(LocalDate.of(2022, 5, 25), 10);
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(keysOf(s, parsed.subList(0, 9)));
        when(repository.findByAccountIdAndPeriodEnd(accountId, pEnd)).thenReturn(List.of());

        StatementPersistenceService.Refreshed r = refresh(accountId, "new", bankDraft(pStart, pEnd, 10), parsed).orElseThrow();

        assertEquals(s, r.statement());
        assertEquals(pStart, s.getPeriodStart());
        assertEquals(pEnd, s.getPeriodEnd());
        assertTrue(r.changedFields().contains("period"));
        verify(repository).save(s);
    }

    @Test
    void aYearlyParseDoesNotAbsorbAMonthlyNorTheReverse() {
        LocalDate yStart = LocalDate.of(2025, 4, 1);
        LocalDate yEnd = LocalDate.of(2026, 3, 31);
        Statement monthly = stored(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 30));
        candidates(monthly);
        List<ParsedStatementLine> yearLines = daily(yStart, 300);
        // The monthly's 20 links all pair, but they are under half of the yearly parse.
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any()))
                .thenReturn(keysOf(monthly, yearLines.subList(280, 300)));

        assertTrue(refresh(accountId, "new", bankDraft(yStart, yEnd, 300), yearLines).isEmpty());

        // A monthly parse against a yearly stored statement (different end): its 20 lines are under 80% of the yearly's links.
        Statement yearly = stored(yStart, LocalDate.of(2026, 3, 30));
        candidates(yearly);
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(keysOf(yearly, yearLines));
        assertTrue(refresh(accountId, "new", bankDraft(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 20),
                yearLines.subList(280, 300)).isEmpty());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void twoEquallyPairedCandidatesAreAmbiguousAndNothingIsRefreshed() {
        LocalDate pStart = LocalDate.of(2026, 6, 1);
        LocalDate pEnd = LocalDate.of(2026, 6, 30);
        Statement a = stored(LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 28));
        Statement b = stored(LocalDate.of(2026, 6, 3), LocalDate.of(2026, 7, 2));
        candidates(a, b);
        List<ParsedStatementLine> parsed = daily(LocalDate.of(2026, 6, 5), 10);
        List<StatementTransactionRepository.StatementLineKey> both = new java.util.ArrayList<>(keysOf(a, parsed));
        both.addAll(keysOf(b, parsed));
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(both);

        assertTrue(refresh(accountId, "new", bankDraft(pStart, pEnd, 10), parsed).isEmpty());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aNewPeriodThatCollidesWithAnotherStatementsEndWritesNothing() {
        LocalDate pStart = LocalDate.of(2026, 6, 1);
        LocalDate pEnd = LocalDate.of(2026, 6, 30);
        Statement target = stored(LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 28));
        Statement other = stored(LocalDate.of(2026, 5, 15), pEnd);
        candidates(target, other);
        List<ParsedStatementLine> parsed = daily(LocalDate.of(2026, 6, 5), 10);
        List<StatementTransactionRepository.StatementLineKey> stored = new java.util.ArrayList<>(keysOf(target, parsed));
        stored.add(key(other.getId(), LocalDate.of(2026, 5, 20), new BigDecimal("999"), TransactionType.DEBIT));
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(stored);
        when(repository.findByAccountIdAndPeriodEnd(accountId, pEnd)).thenReturn(List.of(other));

        assertTrue(refresh(accountId, "new", bankDraft(pStart, pEnd, 10), parsed).isEmpty());
        assertEquals(LocalDate.of(2026, 6, 28), target.getPeriodEnd());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(links, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void theOnlyLinklessStatementEndingTheSameDayIsRefreshedAndItsStartRewritten() {
        LocalDate pStart = LocalDate.of(2026, 6, 1);
        LocalDate pEnd = LocalDate.of(2026, 6, 30);
        Statement s = stored(LocalDate.of(2026, 5, 16), pEnd);
        candidates(s);
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        List<ParsedStatementLine> parsed = daily(LocalDate.of(2026, 6, 5), 10);

        StatementPersistenceService.Refreshed r = refresh(accountId, "new", bankDraft(pStart, pEnd, 10), parsed).orElseThrow();

        assertEquals(s, r.statement());
        assertEquals(pStart, s.getPeriodStart());
        assertEquals(pEnd, s.getPeriodEnd());
        assertTrue(r.changedFields().contains("period"));
        assertEquals(10, r.missingLines().size());
        verify(repository).save(s);
    }

    @Test
    void twoLinklessStatementsEndingTheSameDayAreAmbiguousAndNothingIsRefreshed() {
        LocalDate pStart = LocalDate.of(2026, 6, 1);
        LocalDate pEnd = LocalDate.of(2026, 6, 30);
        Statement a = stored(LocalDate.of(2026, 5, 16), pEnd);
        Statement b = stored(null, pEnd);
        candidates(a, b);
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertTrue(refresh(accountId, "new", bankDraft(pStart, pEnd, 10), daily(LocalDate.of(2026, 6, 5), 10)).isEmpty());
        assertEquals(LocalDate.of(2026, 5, 16), a.getPeriodStart());
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skippedAndFarAwayStatementsAreNotCandidates() {
        LocalDate pStart = LocalDate.of(2026, 6, 1);
        LocalDate pEnd = LocalDate.of(2026, 6, 30);
        Statement skipped = stored(pStart, pEnd);
        Statement far = stored(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        candidates(skipped, far);
        when(links.findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertTrue(service.refreshReconciled(accountId, "new", bankDraft(pStart, pEnd, 1), List.of(on(pEnd, "5")),
                java.util.Set.of(skipped.getId())).isEmpty());
        verify(links, never()).findLineKeysByStatementIdIn(org.mockito.ArgumentMatchers.any());
    }
}
