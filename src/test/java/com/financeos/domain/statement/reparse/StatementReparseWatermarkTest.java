package com.financeos.domain.statement.reparse;

import com.financeos.core.security.UserContext;
import com.financeos.core.security.UserFilterEnabler;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountBankDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.ingestion.ReconciledRefresh;
import com.financeos.domain.ingestion.StatementOpener;
import com.financeos.domain.ingestion.StatementPasswordFinder;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementParseRecords;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.statement.file.StatementFileStore;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The re-read job and the Gmail ingest watermark: a gmail statement's re-parse is filtered with the ingest rule (lines
 * dated before the account's current ingestFromDate dropped) before it is compared or applied; uploads are not; the
 * summary says {@code dropped=n}; recorded WOULD_CHANGE from before the rule is re-examined once and always settles.
 */
class StatementReparseWatermarkTest {

    private static final int V = 3;
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final LocalDate WM = LocalDate.of(2026, 1, 10);
    private static final String GMAIL = "gmail";

    private StatementRepository statements;
    private AccountRepository accounts;
    private UserRepository users;
    private StatementOpener opener;
    private StatementPersistenceService persistence;
    private ReconciledRefresh refresh;
    private StatementFileStore store;
    private final StatementReparseTest.RecordingTx tm = new StatementReparseTest.RecordingTx();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private final byte[] bytes = "pdf".getBytes();
    private Account account;

    private static final ParsedStatementLine BEFORE_1 = line(LocalDate.of(2026, 1, 3), "10");
    private static final ParsedStatementLine BEFORE_2 = line(LocalDate.of(2026, 1, 9), "20");
    private static final ParsedStatementLine ON = line(WM, "30");
    private static final ParsedStatementLine AFTER = line(LocalDate.of(2026, 1, 20), "40");

    private static ParsedStatementLine line(LocalDate d, String amount) {
        return new ParsedStatementLine(d, new BigDecimal(amount), "DEBIT", "line " + amount, null, null);
    }

    @BeforeEach
    void setUp() {
        statements = mock(StatementRepository.class);
        store = mock(StatementFileStore.class);
        accounts = mock(AccountRepository.class);
        users = mock(UserRepository.class);
        opener = mock(StatementOpener.class);
        persistence = mock(StatementPersistenceService.class);
        refresh = mock(ReconciledRefresh.class);
        account = new Account();
        account.setId(accountId);
        account.setBankDetails(new AccountBankDetails());
        when(accounts.findByIdAndUser_Id(accountId, userId)).thenReturn(Optional.of(account));
        when(store.load(userId, fileId)).thenReturn(Optional.of(bytes));
        when(statements.markReparseStarted(any(), anyInt(), any())).thenReturn(1);
        when(statements.markParsedAtVersion(any(), anyInt(), any())).thenReturn(1);
        reads(BEFORE_1, BEFORE_2, ON, AFTER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void reads(ParsedStatementLine... lines) {
        StatementDraft d = new StatementDraft("bank_account", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), BigDecimal.ZERO,
                new BigDecimal("100"), "HSBC", "xx1234", 1, 0, BigDecimal.TEN, BigDecimal.TEN, "balance_chain",
                new BigDecimal("100"), true, StatementVerdict.AUTO_INGEST, Map.of(), null, Set.of(), 3, "{\"snap\":1}");
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(new StatementOpener.Opened(
                new StatementExtractionResult(true, List.of(lines), "xx1234", d.periodStart(), d.periodEnd(), d, null, "text"), false));
    }

    private StatementReparse job(boolean apply) {
        StatementPasswordFinder finder = mock(StatementPasswordFinder.class);
        when(finder.find(any(), any(), any(), anyBoolean(), any())).thenReturn(new StatementPasswordFinder.Found(null, null, false));
        return new StatementReparse(statements, store, accounts, users, opener, finder, persistence, refresh,
                mock(StatementParseRecords.class), mock(UserFilterEnabler.class), new SimpleMeterRegistry(), tm,
                Clock.fixed(NOW, ZoneOffset.UTC), V, true, apply, 5, 45);
    }

    private void candidate(String source, String outcome, String summary) {
        StatementRepository.ReparseCandidate c = mock(StatementRepository.ReparseCandidate.class);
        when(c.getId()).thenReturn(statementId.toString());
        when(c.getUserId()).thenReturn(userId.toString());
        when(c.getAccountId()).thenReturn(accountId.toString());
        when(c.getFileId()).thenReturn(fileId.toString());
        when(c.getFileSha256()).thenReturn("sha");
        when(c.getSource()).thenReturn(source);
        when(c.getReparseOutcome()).thenReturn(outcome);
        when(c.getReparseSummary()).thenReturn(summary);
        when(statements.findReparseCandidates(anyInt(), anyInt(), any(), anyInt(), anyInt())).thenReturn(List.of(c));
    }

    private Statement target() {
        Statement s = new Statement();
        s.setId(statementId);
        return s;
    }

    private void dryRefreshReturns(List<Integer> missing, List<UUID> extras, int relinked) {
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(), List.of(), missing, extras, 0, relinked);
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(r));
    }

    private void stampedWith(String outcome, String summary) {
        verify(statements).markReparse(eq(statementId), eq(V), any(), eq(outcome), summary == null ? isNull() : eq(summary));
    }

    @SuppressWarnings("unchecked")
    private List<ParsedStatementLine> dryRunLines() {
        ArgumentCaptor<List<ParsedStatementLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(persistence).refreshReconciled(any(), any(), any(), lines.capture(), any());
        return lines.getValue();
    }

    // ---- dry run ----

    @Test
    void aGmailStatementIsComparedWithOnlyTheLinesFromTheWatermarkOn() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(1), List.of(), 0);

        job(false).run();

        assertThat(dryRunLines()).containsExactly(ON, AFTER);
        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=2");
    }

    @Test
    void aLineDatedExactlyOnTheWatermarkIsKept() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        reads(ON);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(false).run();

        assertThat(dryRunLines()).containsExactly(ON);
        stampedWith("SAME", null);
    }

    @Test
    void anAccountWithNoWatermarkIsNotFilteredAndTheGmailSummaryStillSaysDroppedZero() {
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(0, 3), List.of(), 0);

        job(false).run();

        @SuppressWarnings("unchecked") ArgumentCaptor<List<ParsedStatementLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(persistence).refreshReconciled(any(), any(), any(), lines.capture(), any());
        assertThat(lines.getValue()).containsExactly(BEFORE_1, BEFORE_2, ON, AFTER);
        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=2 extras=0 relinked=0 dropped=0");
    }

    @Test
    void aWatermarkThatDropsNothingStillSaysDroppedZero() {
        account.setIngestFromDate(LocalDate.of(2025, 1, 1));
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(2), List.of(), 0);

        job(false).run();

        assertThat(dryRunLines()).hasSize(4);
        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=0");
    }

    @Test
    void whenEveryLineIsBeforeTheWatermarkTheStatementIsSameWithNoRowsToAdd() {
        account.setIngestFromDate(LocalDate.of(2026, 2, 1));
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(false).run();

        assertThat(dryRunLines()).isEmpty();
        verify(statements).markParsedAtVersion(eq(statementId), eq(V), any());
        stampedWith("SAME", null);
    }

    @Test
    void whenEveryLineIsBeforeTheWatermarkAHeaderDifferenceIsStillReportedWithDropped() {
        account.setIngestFromDate(LocalDate.of(2026, 2, 1));
        candidate(GMAIL, null, null);
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(), List.of("closing_balance"), List.of(), List.of());
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(r));

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[closing_balance] cleared=[] addRows=0 extras=0 relinked=0 dropped=4");
    }

    @Test
    void aWatermarkMovedEarlierSinceIngestShowsTheNowInsideLinesAsRowsToAdd() {
        // The ingest dropped BEFORE_1 and BEFORE_2 (watermark 10 Jan); the watermark is now 1 Jan: both are inside the window.
        account.setIngestFromDate(LocalDate.of(2026, 1, 1));
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(0, 1), List.of(), 2);

        job(false).run();

        assertThat(dryRunLines()).containsExactly(BEFORE_1, BEFORE_2, ON, AFTER);
        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=2 extras=0 relinked=2 dropped=0");
    }

    @Test
    void aWatermarkMovedLaterSinceIngestStillComparesFromTheEarliestLinkedRow() {
        // Ingested at watermark 10 Jan (earliest linked row 10 Jan); the watermark is now 15 Jan.
        account.setIngestFromDate(LocalDate.of(2026, 1, 15));
        candidate(GMAIL, null, null);
        when(statements.findEarliestLinkedDate(statementId)).thenReturn(WM);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(false).run();

        assertThat(dryRunLines()).containsExactly(ON, AFTER);
        stampedWith("SAME", null);
    }

    @Test
    void withApplyOnAWatermarkMovedLaterAlsoComparesFromTheEarliestLinkedRowAndAppliesNothing() {
        account.setIngestFromDate(LocalDate.of(2026, 1, 15));
        candidate(GMAIL, null, null);
        when(statements.findEarliestLinkedDate(statementId)).thenReturn(WM);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(true).run();

        assertThat(dryRunLines()).containsExactly(ON, AFTER);
        verify(refresh, never()).apply(any(), any(), any(), any(), any(), any(), any());
        stampedWith("SAME", null);
    }

    @Test
    void anEarliestLinkedRowAfterTheWatermarkChangesNothing() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        when(statements.findEarliestLinkedDate(statementId)).thenReturn(LocalDate.of(2026, 1, 20));
        dryRefreshReturns(List.of(0), List.of(), 0);

        job(false).run();

        assertThat(dryRunLines()).containsExactly(ON, AFTER);
        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=2");
    }

    @Test
    void aStatementWithNoLinkedRowsUsesTheWatermarkAlone() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        when(statements.findEarliestLinkedDate(statementId)).thenReturn(null);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(false).run();

        assertThat(dryRunLines()).containsExactly(ON, AFTER);
    }

    @Test
    void noWatermarkMeansTheEarliestLinkedRowIsNotEvenLookedUp() {
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(false).run();

        verify(statements, never()).findEarliestLinkedDate(any());
    }

    @Test
    void anUploadStatementIsNeverFilteredEvenWhenTheAccountHasAWatermark() {
        account.setIngestFromDate(WM);
        candidate("file_upload", null, null);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(false).run();

        @SuppressWarnings("unchecked") ArgumentCaptor<List<ParsedStatementLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(persistence).refreshReconciled(any(), any(), any(), lines.capture(), any());
        assertThat(lines.getValue()).hasSize(4);
        stampedWith("SAME", null);
    }

    @Test
    void theWatermarkIsReadFromTheAccountAsItIsNow() {
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(), List.of(), 0);
        account.setIngestFromDate(LocalDate.of(2026, 1, 15));

        job(false).run();

        assertThat(dryRunLines()).containsExactly(AFTER);
    }

    // ---- apply ----

    @Test
    void withApplyOnAGmailStatementIsAppliedWithTheFilteredLines() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(1), List.of(), 0);
        User u = new User();
        when(users.getReferenceById(userId)).thenReturn(u);
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(), List.of(), List.of(1), List.of(), 0, 0);
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(
                new ReconciledRefresh.Applied(r, new StatementBatchMatcher.Result(0, 0, 1, 0, 0, List.of()))));

        job(true).run();

        @SuppressWarnings("unchecked") ArgumentCaptor<List<ParsedStatementLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(refresh).apply(eq(u), eq(account), eq(accountId), any(), any(StatementDraft.class), lines.capture(), eq(Set.of()));
        assertThat(lines.getValue()).containsExactly(ON, AFTER);
        stampedWith("REFRESHED", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=2");
    }

    @Test
    void withApplyOnAGmailStatementWithNoWatermarkIsAppliedWithAllLinesAndNoCutoff() {
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(1), List.of(), 0);
        User u = new User();
        when(users.getReferenceById(userId)).thenReturn(u);
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(), List.of(), List.of(1), List.of(), 0, 0);
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(
                new ReconciledRefresh.Applied(r, new StatementBatchMatcher.Result(0, 0, 1, 0, 0, List.of()))));

        job(true).run();

        @SuppressWarnings("unchecked") ArgumentCaptor<List<ParsedStatementLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(refresh).apply(eq(u), eq(account), eq(accountId), any(), any(StatementDraft.class), lines.capture(), eq(Set.of()));
        assertThat(lines.getValue()).hasSize(4);
        stampedWith("REFRESHED", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=0");
    }

    @Test
    void withApplyOnAnUploadStatementIsAppliedWithAllLines() {
        account.setIngestFromDate(WM);
        candidate("file_upload", null, null);
        dryRefreshReturns(List.of(1), List.of(), 0);
        User u = new User();
        when(users.getReferenceById(userId)).thenReturn(u);
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(), List.of(), List.of(1), List.of(), 0, 0);
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(
                new ReconciledRefresh.Applied(r, new StatementBatchMatcher.Result(0, 0, 1, 0, 0, List.of()))));

        job(true).run();

        @SuppressWarnings("unchecked") ArgumentCaptor<List<ParsedStatementLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(refresh).apply(eq(u), eq(account), eq(accountId), any(), any(StatementDraft.class), lines.capture(), eq(Set.of()));
        assertThat(lines.getValue()).hasSize(4);
    }

    @Test
    void withApplyOnAGmailStatementWhoseLinesAreAllBeforeTheWatermarkNothingIsApplied() {
        account.setIngestFromDate(LocalDate.of(2026, 2, 1));
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(true).run();

        verify(refresh, never()).apply(any(), any(), any(), any(), any(), any(), any());
        stampedWith("SAME", null);
    }

    // ---- one-time re-examination ----

    private static final String OLD = "changed=[] cleared=[] addRows=17 extras=0 relinked=0";

    @Test
    void aRecordedGmailWouldChangeThatStillWouldChangeIsRewrittenWithDroppedZero() {
        candidate(GMAIL, "WOULD_CHANGE", OLD);
        dryRefreshReturns(List.of(0, 1, 2), List.of(), 0);

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=3 extras=0 relinked=0 dropped=0");
    }

    @Test
    void aReexaminationThatNowFindsNothingIsSame() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, "WOULD_CHANGE", OLD);
        dryRefreshReturns(List.of(), List.of(), 0);

        job(false).run();

        stampedWith("SAME", null);
    }

    @Test
    void aReexaminationWithDroppedLinesWritesTheActualCount() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, "WOULD_CHANGE", OLD);
        dryRefreshReturns(List.of(0), List.of(), 0);

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=2");
    }

    @Test
    void aGmailWouldChangeThatAlreadyHasDroppedIsRewrittenTheSame() {
        candidate(GMAIL, "WOULD_CHANGE", OLD + " dropped=0");
        dryRefreshReturns(List.of(0), List.of(), 0);

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=0");
    }

    @Test
    void aFirstExaminationOfAGmailStatementSaysDroppedZeroWhenNothingWasDropped() {
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(0), List.of(), 0);

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=0");
    }

    @Test
    void anUploadWouldChangeIsNotMarked() {
        candidate("file_upload", "WOULD_CHANGE", OLD);
        dryRefreshReturns(List.of(0), List.of(), 0);

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0");
    }

    @Test
    void aGmailSummaryWithNoRowsToAddAlsoSaysDropped() {
        candidate(GMAIL, "WOULD_CHANGE", "changed=[period] cleared=[] addRows=0 extras=0 relinked=0");
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(), List.of("period"), List.of(), List.of());
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(r));

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[period] cleared=[] addRows=0 extras=0 relinked=0 dropped=0");
    }

    @Test
    void theDroppedMarkSurvivesTheFiveHundredCharacterTrim() {
        candidate(GMAIL, "WOULD_CHANGE", OLD);
        List<String> longFields = new ArrayList<>();
        for (int i = 0; i < 60; i++) longFields.add("some_long_field_name_" + i);
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(), longFields, List.of(0), List.of());
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(r));

        job(false).run();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(statements).markReparse(eq(statementId), eq(V), any(), eq("WOULD_CHANGE"), summary.capture());
        assertThat(summary.getValue()).hasSize(StatementReparse.SUMMARY_MAX).endsWith(" dropped=0");
    }

    @Test
    void summariseAddsDroppedOnlyWhenAboveZeroOrAlwaysAndPreWatermarkWhenGiven() {
        assertThat(StatementReparse.summarise(List.of(), List.of(), 1, 0, 0, 0, false, -1)).doesNotContain("dropped").doesNotContain("preWatermark");
        assertThat(StatementReparse.summarise(List.of(), List.of(), 1, 0, 0, 7, false, -1)).endsWith(" dropped=7");
        assertThat(StatementReparse.summarise(List.of(), List.of(), 1, 0, 0, 0, true, -1)).endsWith(" dropped=0");
        assertThat(StatementReparse.summarise(List.of(), List.of(), 1, 0, 0, 2, true, 0)).endsWith(" dropped=2 preWatermark=0");
        assertThat(StatementReparse.summarise(List.of(), List.of(), 1, 0, 0)).isEqualTo("changed=[] cleared=[] addRows=1 extras=0 relinked=0");
    }

    // ---- the cutoff reached back before the watermark (a linked row dated before its line) ----

    @Test
    void aCutoffBeforeTheWatermarkReportsHowManyComparedLinesArePreWatermark() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        when(statements.findEarliestLinkedDate(statementId)).thenReturn(LocalDate.of(2026, 1, 8));
        dryRefreshReturns(List.of(0), List.of(), 0);

        job(false).run();

        assertThat(dryRunLines()).containsExactly(BEFORE_2, ON, AFTER);
        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=1 preWatermark=1");
    }

    @Test
    void withApplyOnAMissingLineDatedBeforeTheWatermarkIsNeverCreatedTheStatementIsApplySkipped() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        when(statements.findEarliestLinkedDate(statementId)).thenReturn(LocalDate.of(2026, 1, 8));
        dryRefreshReturns(List.of(0), List.of(), 0); // index 0 of [BEFORE_2, ON, AFTER] is dated 9 Jan

        job(true).run();

        verify(refresh, never()).apply(any(), any(), any(), any(), any(), any(), any());
        stampedWith("APPLY_SKIPPED", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=1 preWatermark=1");
    }

    @Test
    void withApplyOnAMissingLineOnOrAfterTheWatermarkIsStillAppliedEvenWhenTheCutoffReachedBack() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        when(statements.findEarliestLinkedDate(statementId)).thenReturn(LocalDate.of(2026, 1, 8));
        dryRefreshReturns(List.of(2), List.of(), 0); // AFTER
        User u = new User();
        when(users.getReferenceById(userId)).thenReturn(u);
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(), List.of(), List.of(2), List.of(), 0, 0);
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(
                new ReconciledRefresh.Applied(r, new StatementBatchMatcher.Result(0, 0, 1, 0, 0, List.of()))));

        job(true).run();

        stampedWith("REFRESHED", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=1 preWatermark=1");
    }

    @Test
    void withNoReachBackNoPreWatermarkFigureIsWritten() {
        account.setIngestFromDate(WM);
        candidate(GMAIL, null, null);
        dryRefreshReturns(List.of(0), List.of(), 0);

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=1 extras=0 relinked=0 dropped=2");
    }
}
