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
import com.financeos.statement.parser.StatementParseException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class StatementReparseTest {

    private static final int V = 3;
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    /** Records, per transaction, whether it ended committed; hands out real statuses so setRollbackOnly is observable. */
    static class RecordingTx implements PlatformTransactionManager {
        final List<Boolean> committed = new ArrayList<>();
        final AtomicInteger started = new AtomicInteger();
        int active;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            started.incrementAndGet();
            active++;
            return new SimpleTransactionStatus(true);
        }

        @Override
        public void commit(TransactionStatus status) {
            active--;
            committed.add(!status.isRollbackOnly());
        }

        @Override
        public void rollback(TransactionStatus status) {
            active--;
            committed.add(false);
        }

        /** Index (0-based) of the transaction open right now. */
        int current() {
            return started.get() - 1;
        }
    }

    /** Moves forward by a step every time it is read, so a time budget can run out mid-run. */
    static class SteppingClock extends Clock {
        private Instant now = NOW;
        private final long stepSeconds;

        SteppingClock(long stepSeconds) {
            this.stepSeconds = stepSeconds;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Instant r = now;
            now = now.plusSeconds(stepSeconds);
            return r;
        }
    }

    private StatementRepository statements;
    private StatementFileStore store;
    private AccountRepository accounts;
    private UserRepository users;
    private StatementOpener opener;
    private StatementPasswordFinder finder;
    private StatementPersistenceService persistence;
    private ReconciledRefresh refresh;
    private StatementParseRecords records;
    private UserFilterEnabler filter;
    private SimpleMeterRegistry meters;
    private RecordingTx tm;
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private final byte[] bytes = "pdf".getBytes();
    private Account account;

    @BeforeEach
    void setUp() {
        statements = mock(StatementRepository.class);
        store = mock(StatementFileStore.class);
        accounts = mock(AccountRepository.class);
        users = mock(UserRepository.class);
        opener = mock(StatementOpener.class);
        finder = mock(StatementPasswordFinder.class);
        when(finder.find(any(), any(), any(), anyBoolean(), any())).thenReturn(new StatementPasswordFinder.Found(null, null, false));
        persistence = mock(StatementPersistenceService.class);
        refresh = mock(ReconciledRefresh.class);
        records = mock(StatementParseRecords.class);
        filter = mock(UserFilterEnabler.class);
        meters = new SimpleMeterRegistry();
        tm = new RecordingTx();
        account = new Account();
        account.setId(accountId);
        account.setBankDetails(new AccountBankDetails());
        when(accounts.findByIdAndUser_Id(accountId, userId)).thenReturn(Optional.of(account));
        when(store.load(userId, fileId)).thenReturn(Optional.of(bytes));
        selection(statementId);
        when(statements.markReparseStarted(any(), anyInt(), any())).thenReturn(1);
        when(statements.markParsedAtVersion(any(), anyInt(), any())).thenReturn(1);
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(DEFAULT_OPENED);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }

    private final StatementOpener.Opened DEFAULT_OPENED = opened(good(draft(StatementVerdict.AUTO_INGEST)));

    private StatementReparse job(boolean apply, int batch, long budgetSeconds, Clock clock, boolean enabled) {
        return new StatementReparse(statements, store, accounts, users, opener, finder, persistence, refresh, records, filter,
                meters, tm, clock, V, enabled, apply, batch, budgetSeconds);
    }

    private StatementReparse job(boolean apply) {
        return job(apply, 5, 45, Clock.fixed(NOW, ZoneOffset.UTC), true);
    }

    private StatementRepository.ReparseCandidate candidate(UUID id) {
        StatementRepository.ReparseCandidate c = mock(StatementRepository.ReparseCandidate.class);
        when(c.getId()).thenReturn(id.toString());
        when(c.getUserId()).thenReturn(userId.toString());
        when(c.getAccountId()).thenReturn(accountId.toString());
        when(c.getFileId()).thenReturn(fileId.toString());
        when(c.getFileSha256()).thenReturn("sha");
        return c;
    }

    private void selection(UUID... ids) {
        List<StatementRepository.ReparseCandidate> list = new ArrayList<>();
        for (UUID id : ids) list.add(candidate(id));
        when(statements.findReparseCandidates(anyInt(), anyInt(), any(), anyInt(), anyInt())).thenReturn(list);
    }

    private static StatementDraft draft(StatementVerdict verdict) {
        return new StatementDraft("bank_account", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), BigDecimal.ZERO,
                new BigDecimal("100"), "HSBC", "xx1234", 1, 0, BigDecimal.TEN, BigDecimal.TEN, "balance_chain",
                new BigDecimal("100"), true, verdict, Map.of(), null, Set.of(), 3, "{\"snap\":1}");
    }

    private static StatementExtractionResult good(StatementDraft draft) {
        return new StatementExtractionResult(true,
                List.of(new ParsedStatementLine(LocalDate.of(2026, 1, 5), BigDecimal.TEN, "DEBIT", "Coffee", null, null)),
                "xx1234", draft.periodStart(), draft.periodEnd(), draft, null, "text");
    }

    private static StatementOpener.Opened opened(StatementExtractionResult r) {
        return new StatementOpener.Opened(r, false);
    }

    private Statement target(UUID id) {
        Statement s = new Statement();
        s.setId(id);
        return s;
    }

    private void refreshReturns(List<String> fields, List<Integer> missing, List<UUID> extras) {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(target(statementId), fields, missing, extras)));
    }

    private void stampedWith(String outcome, String summary) {
        verify(statements).markReparse(eq(statementId), eq(V), any(), eq(outcome), summary == null ? isNull() : eq(summary));
    }

    private double count(String outcome, boolean applied) {
        var c = meters.find("financeos.statement.reparse").tag("outcome", outcome).tag("applied", String.valueOf(applied)).counter();
        return c == null ? 0 : c.count();
    }

    // ---- outcomes ----

    @Test
    void aMissingFileIsFileMissing() {
        when(store.load(userId, fileId)).thenReturn(Optional.empty());

        job(false).run();

        stampedWith("FILE_MISSING", null);
        verifyNoInteractions(opener, persistence);
    }

    @Test
    void aFailedParseRecordsOnlyTheKind() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(
                StatementExtractionResult.failure("ERROR: row 4 'Rahul Sharma 5,000' unreadable", StatementParseException.Kind.UNREADABLE)));

        job(false).run();

        stampedWith("PARSE_FAILED", "UNREADABLE");
        verifyNoInteractions(persistence);
    }

    @Test
    void aFailureWithoutAKindIsUnknown() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(StatementExtractionResult.failure("x")));

        job(false).run();

        stampedWith("PARSE_FAILED", "UNKNOWN");
    }

    @Test
    void aLockedFileWithNoPasswordThatWorksIsParseFailedLocked() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(
                StatementExtractionResult.failure("ERROR: PDF is password-protected", StatementParseException.Kind.LOCKED)));

        job(false).run();

        stampedWith("PARSE_FAILED", "LOCKED");
    }

    @Test
    void theAccountsSavedPasswordIsHandedToTheOpenerInReadOnlyMode() {
        ((AccountBankDetails) account.getBankDetails()).setStatementPassword("secret");

        job(false).run();

        verify(opener).open(eq(userId), eq(accountId), eq(bytes), isNull(), eq("secret"), eq(false));
    }

    @Test
    void aGateBlockedParseIsBlockedWithTheReasonName() {
        StatementDraft d = draft(StatementVerdict.AUTO_INGEST);
        StatementExtractionResult noNumber = new StatementExtractionResult(true, List.of(), null, null, null, d, null, null);
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(noNumber));

        job(false).run();

        stampedWith("BLOCKED", "NOT_A_STATEMENT");
    }

    @Test
    void aParseWithNoLinesIsEmpty() {
        StatementDraft d = draft(StatementVerdict.AUTO_INGEST);
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(
                new StatementExtractionResult(true, List.of(), "xx1234", d.periodStart(), d.periodEnd(), d, null, null)));

        job(false).run();

        stampedWith("EMPTY", null);
        verifyNoInteractions(persistence);
    }

    @Test
    void aParseThatDoesNotReconcileIsNotReconciledWithVerdictAndMode() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(good(draft(StatementVerdict.NEEDS_REVIEW))));

        job(false).run();

        stampedWith("NOT_RECONCILED", "verdict=NEEDS_REVIEW mode=balance_chain");
        verifyNoInteractions(persistence);
        verify(statements, never()).markParsedAtVersion(any(), anyInt(), any());
    }

    @Test
    void aStatementTheRefreshCannotMatchIsNoTarget() {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        job(false).run();

        stampedWith("NO_TARGET", null);
        verify(statements, never()).markParsedAtVersion(any(), anyInt(), any());
    }

    @Test
    void aRefreshThatPicksAnotherStatementIsNoTargetNotAChange() {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(target(UUID.randomUUID()), List.of("period"), List.of(), List.of())));

        job(true).run();

        stampedWith("NO_TARGET", null);
        verifyNoInteractions(refresh);
    }

    @Test
    void anExceptionIsErrorAndNeverEscapes() {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("Rahul 5000"));

        job(false).run();

        stampedWith("ERROR", null);
    }

    @Test
    void anAccountThatIsNotTheUsersIsNoTarget() {
        when(accounts.findByIdAndUser_Id(accountId, userId)).thenReturn(Optional.empty());

        job(false).run();

        stampedWith("NO_TARGET", "account");
        verifyNoInteractions(opener);
    }

    // ---- SAME ----

    @Test
    void sameStampsTheParserVersionAndWritesTheRecordAfterCommit() {
        refreshReturns(List.of(), List.of(), List.of());

        job(false).run();

        verify(statements).markParsedAtVersion(statementId, V, NOW);
        stampedWith("SAME", null);
        verify(records).saveQuietly(statementId, 3, "{\"snap\":1}");
        // parking stamp, account read (committed), dry run (rolled back), the stamp transaction (committed); the record is written after.
        assertThat(tm.committed).containsExactly(true, true, false, true);
    }

    @Test
    void sameThatCommitsComesAfterTheDryRunRolledBack() {
        refreshReturns(List.of(), List.of(), List.of());
        doAnswer(inv -> {
            assertThat(tm.active).isZero();
            return null;
        }).when(records).saveQuietly(any(), any(), any());

        job(false).run();

        verify(records).saveQuietly(any(), any(), any());
        assertThat(tm.committed.get(2)).isFalse(); // the dry run
    }

    // ---- WOULD_CHANGE / REFRESHED ----

    @Test
    void wouldChangeChangesNothingAndTheSimulatedTransactionIsRolledBack() {
        AtomicInteger refreshTx = new AtomicInteger(-1);
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            refreshTx.set(tm.current());
            return Optional.of(new StatementPersistenceService.Refreshed(target(statementId), List.of("closing_balance", "parse_mode"),
                    List.of(2), List.of(UUID.randomUUID(), UUID.randomUUID())));
        });

        job(false).run();

        assertThat(refreshTx.get()).isGreaterThanOrEqualTo(0);
        assertThat(tm.committed.get(refreshTx.get())).isFalse();
        stampedWith("WOULD_CHANGE", "changed=[closing_balance,parse_mode] cleared=[] addRows=1 extras=2 relinked=0");
        verifyNoInteractions(refresh, records);
        verify(statements, never()).markParsedAtVersion(any(), anyInt(), any());
        verify(statements, never()).save(any());
    }

    @Test
    void withApplyOffNeitherTheRealRefreshNorTheMatcherRunsAndOnlyReparseColumnsAreWritten() {
        refreshReturns(List.of("period"), List.of(1), List.of());
        List<Integer> touchingTx = new ArrayList<>();
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            touchingTx.add(tm.current());
            return Optional.of(new StatementPersistenceService.Refreshed(target(statementId), List.of("period"), List.of(1), List.of()));
        });

        job(false).run();

        // every transaction that touched the statement/links ended rolled back
        assertThat(touchingTx).isNotEmpty().allSatisfy(i -> assertThat(tm.committed.get(i)).isFalse());
        verify(statements).findReparseCandidates(eq(5), eq(V), any(), eq(3), eq(0));
        verify(statements).markReparse(any(), anyInt(), any(), anyString(), any());
        verify(statements).markReparseStarted(statementId, V, NOW);
        verify(statements).findById(statementId); // the read of the stored values before the simulated refresh
        verifyNoMoreInteractions(statements);
        verifyNoInteractions(refresh, records, users);
        verify(accounts, never()).save(any());
    }

    @Test
    void withApplyOnTheRealRefreshRunsOnceInOneCommittedTransactionAndIsRecordedRefreshed() {
        refreshReturns(List.of("period"), List.of(1), List.of());
        User u = new User();
        when(users.getReferenceById(userId)).thenReturn(u);
        AtomicInteger applyTx = new AtomicInteger(-1);
        StatementPersistenceService.Refreshed r = new StatementPersistenceService.Refreshed(target(statementId),
                List.of("period", "closing_balance"), List.of(1, 2), List.of(UUID.randomUUID()));
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            applyTx.set(tm.current());
            return Optional.of(new ReconciledRefresh.Applied(r, new StatementBatchMatcher.Result(0, 0, 2, 0, 0, List.of())));
        });

        job(true).run();

        verify(refresh, times(1)).apply(eq(u), eq(account), eq(accountId), eq(com.financeos.domain.statement.StatementPersistenceService.sha256Hex(bytes)),
                any(StatementDraft.class), any(), eq(Set.of()));
        assertThat(tm.committed.get(applyTx.get())).isTrue();
        verify(records).saveQuietly(statementId, 3, "{\"snap\":1}");
        stampedWith("REFRESHED", "changed=[period,closing_balance] cleared=[] addRows=2 extras=1 relinked=0");
        assertThat(count("REFRESHED", true)).isEqualTo(1);
    }

    @Test
    void withApplyOnAnApplyThatFindsNoTargetRollsBackAndIsNoTarget() {
        refreshReturns(List.of("period"), List.of(), List.of());
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        job(true).run();

        stampedWith("NO_TARGET", null);
        verifyNoInteractions(records);
        assertThat(tm.committed.get(tm.committed.size() - 2)).isFalse();
    }

    @Test
    void withApplyOnAnIdenticalParseStillOnlyStampsLikeSame() {
        refreshReturns(List.of(), List.of(), List.of());

        job(true).run();

        stampedWith("SAME", null);
        verifyNoInteractions(refresh);
    }

    // ---- run-level rules ----

    @Test
    void theOutcomeCounterIsTaggedWithOutcomeAndApplied() {
        refreshReturns(List.of("period"), List.of(), List.of());

        job(false).run();

        assertThat(count("WOULD_CHANGE", false)).isEqualTo(1);
    }

    @Test
    void disabledDoesNothing() {
        job(false, 5, 45, Clock.fixed(NOW, ZoneOffset.UTC), false).run();

        verifyNoInteractions(statements, store);
    }

    @Test
    void aZeroBatchDoesNothing() {
        job(false, 0, 45, Clock.fixed(NOW, ZoneOffset.UTC), true).run();

        verifyNoInteractions(statements, store);
    }

    @Test
    void theBatchSizeAndCurrentVersionGoToTheSelection() {
        when(statements.findReparseCandidates(anyInt(), anyInt(), any(), anyInt(), anyInt())).thenReturn(List.of());

        job(false, 7, 45, Clock.fixed(NOW, ZoneOffset.UTC), true).run();

        verify(statements).findReparseCandidates(eq(7), eq(V), any(), eq(3), eq(0));
        verifyNoInteractions(store);
    }

    @Test
    void theTimeBudgetStopsTheRunBeforeTheNextStatement() {
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        selection(statementId, second, third);
        when(store.load(any(), any())).thenReturn(Optional.empty());

        // reads (2s apart): deadline = t0+5s; selection t0+2s; check 1 t0+4s (inside); parking t0+6s; stamp t0+8s; check 2 t0+10s (spent)
        job(false, 5, 5, new SteppingClock(2), true).run();

        verify(store, times(1)).load(any(), any());
        verify(statements, times(1)).markReparse(any(), anyInt(), any(), anyString(), any());
    }

    @Test
    void anAlreadySpentBudgetExaminesNothing() {
        job(false, 5, 0, Clock.fixed(NOW, ZoneOffset.UTC), true).run();

        verifyNoInteractions(store);
    }

    @Test
    void oneStatementsFailureDoesNotStopTheNext() {
        UUID second = UUID.randomUUID();
        selection(statementId, second);
        when(store.load(userId, fileId)).thenThrow(new IllegalStateException("boom")).thenReturn(Optional.empty());

        job(false).run();

        verify(statements).markReparse(eq(statementId), eq(V), any(), eq("ERROR"), isNull());
        verify(statements).markReparse(eq(second), eq(V), any(), eq("FILE_MISSING"), isNull());
    }

    @Test
    void aFailingStampDoesNotStopTheRun() {
        UUID second = UUID.randomUUID();
        selection(statementId, second);
        when(store.load(any(), any())).thenReturn(Optional.empty());
        when(statements.markReparse(eq(statementId), anyInt(), any(), anyString(), any())).thenThrow(new IllegalStateException("db"));

        job(false).run();

        verify(statements).markReparse(eq(second), eq(V), any(), eq("FILE_MISSING"), isNull());
    }

    @Test
    void aFailingSelectionEndsTheRunQuietly() {
        when(statements.findReparseCandidates(anyInt(), anyInt(), any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("db"));

        job(false).run();

        verifyNoInteractions(store);
    }

    @Test
    void anUnusableCandidateIdIsErrorAndTheNextStillRuns() {
        StatementRepository.ReparseCandidate bad = mock(StatementRepository.ReparseCandidate.class);
        when(bad.getId()).thenReturn("not-a-uuid");
        StatementRepository.ReparseCandidate ok = candidate(statementId);
        when(statements.findReparseCandidates(anyInt(), anyInt(), any(), anyInt(), anyInt())).thenReturn(List.of(bad, ok));
        when(store.load(any(), any())).thenReturn(Optional.empty());

        job(false).run();

        verify(statements).markReparse(eq(statementId), eq(V), any(), eq("FILE_MISSING"), isNull());
        assertThat(count("ERROR", false)).isEqualTo(1);
    }

    // ---- tenancy ----

    @Test
    void everyLoadAndRefreshUsesTheStatementsOwnUserAndAccountAndTheUserContextIsSetThenRestored() {
        UUID other = UUID.randomUUID();
        UserContext.setCurrentUserId(other);
        List<UUID> seen = new ArrayList<>();
        when(store.load(any(), any())).thenAnswer(inv -> {
            seen.add(UserContext.getCurrentUserId());
            return Optional.of(bytes);
        });
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            seen.add(UserContext.getCurrentUserId());
            return Optional.of(new StatementPersistenceService.Refreshed(target(statementId), List.of(), List.of(), List.of()));
        });

        job(false).run();

        verify(store).load(userId, fileId);
        verify(accounts, atLeast(1)).findByIdAndUser_Id(accountId, userId);
        verify(persistence).refreshReconciled(eq(accountId), anyString(), any(), any(), any());
        verify(filter, atLeast(1)).enable();
        assertThat(seen).containsOnly(userId);
        assertThat(UserContext.getCurrentUserId()).isEqualTo(other);
    }

    @Test
    void theUserContextIsClearedAfterARunWhenThereWasNone() {
        job(false).run();

        assertThat(UserContext.getCurrentUserId()).isNull();
    }

    @Test
    void theUserContextIsRestoredEvenWhenAStatementFails() {
        when(store.load(any(), any())).thenThrow(new IllegalStateException("boom"));

        job(false).run();

        assertThat(UserContext.getCurrentUserId()).isNull();
    }

    // ---- no LLM ----

    @Test
    void theJobHasNoLlmOrFillerCollaborator() {
        for (var ctor : StatementReparse.class.getDeclaredConstructors()) {
            for (Class<?> t : ctor.getParameterTypes()) {
                assertThat(t.getName()).doesNotContainIgnoringCase("filler").doesNotContain("llm");
            }
        }
        for (var f : StatementReparse.class.getDeclaredFields()) {
            assertThat(f.getType().getName()).doesNotContainIgnoringCase("filler").doesNotContain("llm");
        }
    }

    // ---- changed / cleared ----

    /** The stored statement as it is before any refresh: a closing balance, a mode, and no opening balance. */
    private Statement stored(BigDecimal closing, BigDecimal opening) {
        Statement s = target(statementId);
        s.setClosingBalance(closing);
        s.setOpeningBalance(opening);
        s.setParseMode("balance_chain");
        return s;
    }

    @Test
    void aFieldTheParseHasNoValueForIsClearedNotChangedAndIsWouldClear() {
        // draft(): closing 100, opening 0, totals 10; stored has total_credits but the draft has it... use a null-valued draft
        StatementDraft noCredits = new StatementDraft("bank_account", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                BigDecimal.ZERO, new BigDecimal("100"), "HSBC", "xx1234", 1, 0, BigDecimal.TEN, null, "balance_chain",
                new BigDecimal("100"), true, StatementVerdict.AUTO_INGEST, Map.of(), null, Set.of(), 3, "{}");
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(good(noCredits)));
        Statement st = stored(new BigDecimal("100"), BigDecimal.ZERO);
        st.setTotalCredits(BigDecimal.TEN);
        when(statements.findById(statementId)).thenReturn(Optional.of(st));
        refreshReturns(List.of("total_credits"), List.of(), List.of());

        job(false).run();

        stampedWith("WOULD_CLEAR", "changed=[] cleared=[total_credits] addRows=0 extras=0 relinked=0");
    }

    @Test
    void aFieldFilledFromEmptyOrReplacedIsChanged() {
        Statement st = stored(new BigDecimal("90"), null);
        when(statements.findById(statementId)).thenReturn(Optional.of(st));
        refreshReturns(List.of("opening_balance", "closing_balance"), List.of(), List.of());

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[opening_balance,closing_balance] cleared=[] addRows=0 extras=0 relinked=0");
    }

    private StatementDraft draftWithoutCredits() {
        return new StatementDraft("bank_account", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                BigDecimal.ZERO, new BigDecimal("100"), "HSBC", "xx1234", 1, 0, BigDecimal.TEN, null, "balance_chain",
                new BigDecimal("100"), true, StatementVerdict.AUTO_INGEST, Map.of(), null, Set.of(), 3, "{}");
    }

    @Test
    void clearedOnlyWithApplyOnIsNotAppliedAndIsWouldClear() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(good(draftWithoutCredits())));
        Statement st = stored(new BigDecimal("100"), BigDecimal.ZERO);
        st.setTotalCredits(BigDecimal.TEN);
        when(statements.findById(statementId)).thenReturn(Optional.of(st));
        refreshReturns(List.of("total_credits"), List.of(), List.of());

        job(true).run();

        stampedWith("WOULD_CLEAR", "changed=[] cleared=[total_credits] addRows=0 extras=0 relinked=0");
        verifyNoInteractions(refresh, records);
        assertThat(count("REFRESHED", true)).isZero();
    }

    @Test
    void changedAndClearedWithApplyOnAppliesTheChangeAndKeepsTheClearedValue() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(good(draftWithoutCredits())));
        Statement st = stored(new BigDecimal("90"), BigDecimal.ZERO);
        st.setTotalCredits(new BigDecimal("77"));
        when(statements.findById(statementId)).thenReturn(Optional.of(st));
        refreshReturns(List.of("closing_balance", "total_credits"), List.of(), List.of()); // dry run
        when(users.getReferenceById(userId)).thenReturn(new User());
        // The real refresh overwrites the managed entity from the draft, clearing total_credits.
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            st.setClosingBalance(new BigDecimal("100"));
            st.setTotalCredits(null);
            return Optional.of(new ReconciledRefresh.Applied(new StatementPersistenceService.Refreshed(st,
                    List.of("closing_balance", "total_credits"), List.of(), List.of()), null));
        });

        job(true).run();

        assertThat(st.getClosingBalance()).isEqualByComparingTo("100");
        assertThat(st.getTotalCredits()).isEqualByComparingTo("77");
        stampedWith("REFRESHED", "changed=[closing_balance] cleared=[total_credits] addRows=0 extras=0 relinked=0");
        verify(records).saveQuietly(statementId, 3, "{}");
    }

    @Test
    void addedRowsWithAClearedFieldAreAppliedAndTheClearedValueKept() {
        when(opener.open(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(opened(good(draftWithoutCredits())));
        Statement st = stored(new BigDecimal("100"), BigDecimal.ZERO);
        st.setTotalCredits(BigDecimal.TEN);
        when(statements.findById(statementId)).thenReturn(Optional.of(st));
        refreshReturns(List.of("total_credits"), List.of(3), List.of());
        when(users.getReferenceById(userId)).thenReturn(new User());
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            st.setTotalCredits(null);
            return Optional.of(new ReconciledRefresh.Applied(new StatementPersistenceService.Refreshed(st,
                    List.of("total_credits"), List.of(3), List.of()), null));
        });

        job(true).run();

        assertThat(st.getTotalCredits()).isEqualByComparingTo("10");
        stampedWith("REFRESHED", "changed=[] cleared=[total_credits] addRows=1 extras=0 relinked=0");
    }

    @Test
    void theSummaryFormatListsChangedClearedAndCountsAndIsTrimmedTo500() {
        assertThat(StatementReparse.summarise(List.of("period", "verdict"), List.of("total_credits"), 2, 1, 4))
                .isEqualTo("changed=[period,verdict] cleared=[total_credits] addRows=2 extras=1 relinked=4");
        assertThat(StatementReparse.summarise(List.of(), List.of(), 0, 0, 0)).isEqualTo("changed=[] cleared=[] addRows=0 extras=0 relinked=0");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) many.add("field_number_" + i);
        assertThat(StatementReparse.summarise(many, List.of(), 4, 5, 0)).hasSize(500).startsWith("changed=[field_number_0,field_number_1");
    }

    // ---- relinked ----

    private StatementPersistenceService.Refreshed relinked(int n) {
        return new StatementPersistenceService.Refreshed(target(statementId), List.of(), List.of(), List.of(), 0, n);
    }

    @Test
    void relinkedLinksAloneAreADifferenceNotSame() {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(relinked(2)));

        job(false).run();

        stampedWith("WOULD_CHANGE", "changed=[] cleared=[] addRows=0 extras=0 relinked=2");
        verify(statements, never()).markParsedAtVersion(any(), anyInt(), any());
    }

    @Test
    void relinkedLinksAreAppliedWhenApplyIsOn() {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(relinked(3)));
        when(users.getReferenceById(userId)).thenReturn(new User());
        when(refresh.apply(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(
                new ReconciledRefresh.Applied(relinked(3), null)));

        job(true).run();

        stampedWith("REFRESHED", "changed=[] cleared=[] addRows=0 extras=0 relinked=3");
    }

    @Test
    void keptEditedRowsAreNotADifference() {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(
                new StatementPersistenceService.Refreshed(target(statementId), List.of(), List.of(), List.of(), 4, 0)));

        job(false).run();

        stampedWith("SAME", null);
    }

    // ---- poison pill ----

    @Test
    void theStatementIsParkedInProgressBeforeAnythingIsLoaded() {
        doAnswer(inv -> {
            verifyNoInteractions(store, opener);
            return 1;
        }).when(statements).markReparseStarted(statementId, V, NOW);
        when(store.load(userId, fileId)).thenReturn(Optional.empty());

        job(false).run();

        verify(statements).markReparseStarted(statementId, V, NOW);
        stampedWith("FILE_MISSING", null);
    }

    @Test
    void aStackOverflowIsErrorAndTheNextStatementStillRuns() {
        UUID second = UUID.randomUUID();
        selection(statementId, second);
        when(store.load(userId, fileId)).thenThrow(new StackOverflowError()).thenReturn(Optional.empty());

        job(false).run();

        stampedWith("ERROR", null);
        verify(statements).markReparse(eq(second), eq(V), any(), eq("FILE_MISSING"), isNull());
    }

    @Test
    void anOutOfMemoryErrorPropagatesAfterTheParkingStamp() {
        when(store.load(userId, fileId)).thenThrow(new OutOfMemoryError("test"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> job(false).run()).isInstanceOf(OutOfMemoryError.class);

        verify(statements).markReparseStarted(statementId, V, NOW);
        verify(statements, never()).markReparse(any(), anyInt(), any(), anyString(), any());
        assertThat(UserContext.getCurrentUserId()).isNull();
    }

    @Test
    void aStatementAlreadyStampedByALaterVersionIsSkippedWithoutExamining() {
        when(statements.markReparseStarted(any(), anyInt(), any())).thenReturn(0);

        job(false).run();

        verifyNoInteractions(store, opener);
    }

    // ---- SAME guard ----

    @Test
    void sameWhoseParserVersionStampUpdatedNothingIsSkippedChangedAndWritesNoRecord() {
        refreshReturns(List.of(), List.of(), List.of());
        when(statements.markParsedAtVersion(any(), anyInt(), any())).thenReturn(0);

        job(false).run();

        stampedWith("SKIPPED_CHANGED", null);
        verifyNoInteractions(records);
        verify(statements, never()).markReparse(any(), anyInt(), any(), eq("SAME"), any());
    }

    // ---- retry and apply selection ----

    @Test
    void theSelectionGetsTheRetryWindowAttemptCapAndTheApplyFlag() {
        when(statements.findReparseCandidates(anyInt(), anyInt(), any(), anyInt(), anyInt())).thenReturn(List.of());

        job(true, 5, 45, Clock.fixed(NOW, ZoneOffset.UTC), true).run();

        verify(statements).findReparseCandidates(5, V, NOW.minus(java.time.Duration.ofHours(24)), 3, 1);
    }
}
