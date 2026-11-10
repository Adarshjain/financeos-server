package com.financeos.domain.statement.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementRepository.FileBackfillCandidate;
import com.financeos.domain.statement.file.StatementFileAttacher.AttachResult;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.financeos.gmail.reconcile.StatementOriginalFetcher;
import com.financeos.gmail.reconcile.StatementOriginalFetcher.Kind;
import com.financeos.gmail.reconcile.StatementOriginalFetcher.Result;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;

class StatementFileBackfillTest {

    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    /** The retry window is 24h: only retryable rows tried before this are selected. */
    private static final Instant CUTOFF = NOW.minus(Duration.ofHours(24));

    private final StatementRepository statements = mock(StatementRepository.class);
    private final GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
    private final StatementOriginalFetcher fetcher = mock(StatementOriginalFetcher.class);
    private final StatementFileAttacher attacher = mock(StatementFileAttacher.class);
    private final StatementFileProperties fileProps = new StatementFileProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final StatementFileMetrics metrics = new StatementFileMetrics(registry, mock(StatementFileRepository.class), () -> 0L);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(StatementFileBackfill.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private StatementFileBackfill backfill(boolean enabled, int batch) {
        return backfill(CLOCK, enabled, batch, 45);
    }

    private StatementFileBackfill backfill(Clock clock, boolean enabled, int batch, long budgetSeconds) {
        return new StatementFileBackfill(statements, connections, fetcher, attacher, metrics, fileProps, txManager, clock,
                enabled, batch, 24, budgetSeconds);
    }

    private record Candidate(String id, String userId, String accountId, String sourceRef, String fileSha256)
            implements FileBackfillCandidate {
        @Override public String getId() { return id; }
        @Override public String getUserId() { return userId; }
        @Override public String getAccountId() { return accountId; }
        @Override public String getSourceRef() { return sourceRef; }
        @Override public String getFileSha256() { return fileSha256; }
    }

    private static FileBackfillCandidate candidate(UUID id, UUID userId, String ref, String sha) {
        return new Candidate(id.toString(), userId.toString(), UUID.randomUUID().toString(), ref, sha);
    }

    private void select(FileBackfillCandidate... c) {
        when(statements.findFileBackfillCandidates(anyInt(), any())).thenReturn(List.of(c));
    }

    /** A usable mailbox of the user; the ledger holds nothing unless {@link #ledgerHolds} says so. */
    private GmailConnection mailbox(UUID userId) {
        User u = new User();
        u.setId(userId);
        GmailConnection c = new GmailConnection();
        c.setId(UUID.randomUUID());
        c.setUser(u);
        c.setIsConnected(true);
        List<GmailConnection> all = new java.util.ArrayList<>(connections.findAllByUserId(userId));
        all.add(c);
        when(connections.findAllByUserId(userId)).thenReturn(all);
        return c;
    }

    private void ledgerHolds(UUID userId, String ref, GmailConnection c) {
        when(connections.findOwningMessage(userId, ref)).thenReturn(List.of(c));
    }

    private static Result found() {
        return new Result(Kind.FOUND, new byte[] {1, 2}, "s.pdf");
    }

    private static Result gone() {
        return new Result(Kind.MAIL_GONE, null, null);
    }

    private static Result noMatch() {
        return new Result(Kind.NO_MATCHING_ATTACHMENT, null, null);
    }

    private double count(String outcome) {
        var counter = registry.find("financeos.statement.files.backfill").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private String summary() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("Statement file back-fill {"))
                .findFirst().orElse("");
    }

    private void settledAs(UUID id, String outcome) {
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(statements).markFileBackfill(eq(id), at.capture(), eq(outcome));
        assertEquals(NOW, at.getValue());
    }

    private void notStamped(UUID id) {
        verify(statements, never()).markFileBackfill(eq(id), any(), anyString());
    }

    // ---- outcomes

    @Test
    void attachesTheMatchingFileAndStampsAttached() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection conn = mailbox(user);
        ledgerHolds(user, "m1", conn);
        when(fetcher.fetch(conn, "m1", "sha")).thenReturn(found());
        when(attacher.attachDetailed(eq(user), eq(id), any(byte[].class), eq("s.pdf"))).thenReturn(AttachResult.ATTACHED);

        backfill(true, 10).run();

        settledAs(id, "ATTACHED");
        assertEquals(1, count("ATTACHED"));
    }

    @Test
    void aStatementThatAlreadyHasItsFileCountsAsAttached() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(conn, "m1", "sha")).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.ALREADY_HAD_FILE);

        backfill(true, 10).run();

        settledAs(id, "ATTACHED");
    }

    @Test
    void mailGoneIsFinal() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(conn, "m1", "sha")).thenReturn(gone());

        backfill(true, 10).run();

        settledAs(id, "MAIL_GONE");
        verifyNoInteractions(attacher);
    }

    @Test
    void noMatchingAttachmentIsFinal() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(conn, "m1", "sha")).thenReturn(noMatch());

        backfill(true, 10).run();

        settledAs(id, "NO_MATCHING_ATTACHMENT");
    }

    @Test
    void aPermanentlyUnstorableFileIsNotStoredAndFinal() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(conn, "m1", "sha")).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.NOT_STORED_PERMANENT);

        backfill(true, 10).run();

        settledAs(id, "NOT_STORED");
    }

    @Test
    void aStatementDeletedMeanwhileIsNotStoredAndFinal() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(conn, "m1", "sha")).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.STATEMENT_MISSING);

        backfill(true, 10).run();

        settledAs(id, "NOT_STORED");
    }

    // ---- store unavailable stops the whole run

    @Test
    void aTransientStoreProblemStampsStoreUnavailableAndStopsTheWholeRunWithoutStampingTheRest() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID(), s3 = UUID.randomUUID();
        select(candidate(s1, u1, "a1", "h1"), candidate(s2, u2, "b1", "h2"), candidate(s3, u1, "a2", "h3"));
        GmailConnection c1 = mailbox(u1);
        mailbox(u2);
        when(fetcher.fetch(eq(c1), eq("a1"), eq("h1"))).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.NOT_STORED_TRANSIENT);

        backfill(true, 10).run();

        settledAs(s1, "STORE_UNAVAILABLE");
        notStamped(s2);
        notStamped(s3);
        verify(fetcher, times(1)).fetch(any(), anyString(), anyString());
        assertEquals(1, count("STORE_UNAVAILABLE"));
    }

    @Test
    void disabledFileStorageDoesNothingAtAll() {
        fileProps.setEnabled(false);

        backfill(true, 10).run();

        verifyNoInteractions(statements, connections, fetcher, attacher);
    }

    // ---- connection-level conditions stop the user

    @Test
    void aUserWithNoMailboxIsNoConnectionAndItsSiblingsAreStampedLikeIt() {
        UUID u1 = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        select(candidate(s1, u1, "a1", "h1"), candidate(s2, u1, "a2", "h2"));

        backfill(true, 10).run();

        settledAs(s1, "NO_CONNECTION");
        settledAs(s2, "NO_CONNECTION");
        verifyNoInteractions(fetcher);
        assertEquals(1, count("NO_CONNECTION"));
    }

    @Test
    void disconnectedAndReconnectNeededMailboxesAreNotUsable() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        select(candidate(s1, u1, "a1", "h1"), candidate(s2, u2, "b1", "h2"));
        mailbox(u1).setIsConnected(false);
        mailbox(u2).setAuthFailedAt(Instant.now());

        backfill(true, 10).run();

        verifyNoInteractions(fetcher);
        settledAs(s1, "NO_CONNECTION");
        settledAs(s2, "NO_CONNECTION");
    }

    @Test
    void aConnectionBelongingToAnotherUserIsNeverUsed() {
        UUID user = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        GmailConnection foreign = new GmailConnection();
        foreign.setId(UUID.randomUUID());
        foreign.setUser(stranger);
        foreign.setIsConnected(true);
        when(connections.findAllByUserId(user)).thenReturn(List.of(foreign));

        backfill(true, 10).run();

        verifyNoInteractions(fetcher, attacher);
        settledAs(id, "NO_CONNECTION");
    }

    @Test
    void aGmailFailureIsErrorStopsThatUserStampsItsSiblingsAndLogsIdsOnly() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID(), s3 = UUID.randomUUID();
        select(candidate(s1, u1, "a1", "h1"), candidate(s2, u2, "b1", "h2"), candidate(s3, u1, "a2", "h3"));
        GmailConnection c1 = mailbox(u1);
        GmailConnection c2 = mailbox(u2);
        when(fetcher.fetch(c1, "a1", "h1")).thenThrow(new GmailEngineException(GmailError.RATE_LIMIT, "secret detail"));
        when(fetcher.fetch(c2, "b1", "h2")).thenReturn(found());
        when(attacher.attachDetailed(eq(u2), eq(s2), any(), any())).thenReturn(AttachResult.ATTACHED);

        backfill(true, 10).run();

        settledAs(s1, "ERROR");
        settledAs(s3, "ERROR");
        settledAs(s2, "ATTACHED");
        verify(fetcher, never()).fetch(c1, "a2", "h3");
        String warn = logs.list.stream().filter(e -> e.getLevel().toString().equals("WARN")).findFirst().get().getFormattedMessage();
        assertTrue(warn.contains(s1.toString()) && warn.contains(u1.toString()));
        assertFalse(warn.contains("secret detail"));
    }

    // ---- statement-specific failures do not stop siblings

    @Test
    void aMissingLedgerRowStillTriesTheUsersMailbox() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(conn, "m1", "sha")).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.ATTACHED);

        backfill(true, 10).run();

        settledAs(id, "ATTACHED");
    }

    @Test
    void aMissingLedgerRowDoesNotStopTheUsersHealthySiblings() {
        UUID user = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        select(candidate(s1, user, "gone", "h1"), candidate(s2, user, "m2", "h2"));
        GmailConnection conn = mailbox(user);
        ledgerHolds(user, "m2", conn);
        when(fetcher.fetch(conn, "gone", "h1")).thenReturn(gone());
        when(fetcher.fetch(conn, "m2", "h2")).thenReturn(found());
        when(attacher.attachDetailed(eq(user), eq(s2), any(), any())).thenReturn(AttachResult.ATTACHED);

        backfill(true, 10).run();

        settledAs(s1, "MAIL_GONE");
        settledAs(s2, "ATTACHED");
    }

    @Test
    void withTwoMailboxesTheOneHoldingTheMessageIsFoundWhicheverIsFirst() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection first = mailbox(user);
        GmailConnection second = mailbox(user);
        when(fetcher.fetch(first, "m1", "sha")).thenReturn(gone());
        when(fetcher.fetch(second, "m1", "sha")).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.ATTACHED);

        backfill(true, 10).run();

        settledAs(id, "ATTACHED");
    }

    @Test
    void theMailboxWhoseLedgerHoldsTheMessageIsTriedFirst() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection first = mailbox(user);
        GmailConnection owner = mailbox(user);
        ledgerHolds(user, "m1", owner);
        when(fetcher.fetch(owner, "m1", "sha")).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.ATTACHED);

        backfill(true, 10).run();

        verify(fetcher, never()).fetch(eq(first), anyString(), anyString());
        settledAs(id, "ATTACHED");
    }

    @Test
    void goneFromEveryMailboxIsMailGone() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection a = mailbox(user);
        GmailConnection b = mailbox(user);
        when(fetcher.fetch(a, "m1", "sha")).thenReturn(gone());
        when(fetcher.fetch(b, "m1", "sha")).thenReturn(gone());

        backfill(true, 10).run();

        settledAs(id, "MAIL_GONE");
    }

    @Test
    void foundInOneMailboxWithoutThatHashAndGoneFromTheOtherIsNoMatchingAttachment() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection a = mailbox(user);
        GmailConnection b = mailbox(user);
        when(fetcher.fetch(a, "m1", "sha")).thenReturn(gone());
        when(fetcher.fetch(b, "m1", "sha")).thenReturn(noMatch());

        backfill(true, 10).run();

        settledAs(id, "NO_MATCHING_ATTACHMENT");
    }

    @Test
    void anUnexpectedFailureOnOneStatementIsErrorButItsSiblingsAreStillProcessed() {
        UUID user = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        select(candidate(s1, user, "a1", "h1"), candidate(s2, user, "a2", "h2"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(conn, "a1", "h1")).thenThrow(new IllegalStateException("bug"));
        when(fetcher.fetch(conn, "a2", "h2")).thenReturn(gone());

        backfill(true, 10).run();

        settledAs(s1, "ERROR");
        settledAs(s2, "MAIL_GONE");
    }

    @Test
    void aFinalOutcomeDoesNotStopTheSameUsersNextStatement() {
        UUID u1 = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        select(candidate(s1, u1, "a1", "h1"), candidate(s2, u1, "a2", "h2"));
        GmailConnection c1 = mailbox(u1);
        when(fetcher.fetch(c1, "a1", "h1")).thenReturn(gone());
        when(fetcher.fetch(c1, "a2", "h2")).thenReturn(noMatch());

        backfill(true, 10).run();

        settledAs(s1, "MAIL_GONE");
        settledAs(s2, "NO_MATCHING_ATTACHMENT");
    }

    @Test
    void aFailureWritingTheOutcomeDoesNotStopTheRun() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        select(candidate(s1, u1, "a1", "h1"), candidate(s2, u2, "b1", "h2"));
        GmailConnection c1 = mailbox(u1);
        GmailConnection c2 = mailbox(u2);
        when(fetcher.fetch(c1, "a1", "h1")).thenReturn(gone());
        when(fetcher.fetch(c2, "b1", "h2")).thenReturn(gone());
        when(statements.markFileBackfill(eq(s1), any(), eq("MAIL_GONE"))).thenThrow(new RuntimeException("db"));

        backfill(true, 10).run();

        verify(statements).markFileBackfill(eq(s2), any(), eq("MAIL_GONE"));
        assertEquals(1, count("ERROR"));
        assertEquals(1, count("MAIL_GONE"));
    }

    // ---- retry window and selection

    @Test
    void theSelectionAsksForRetryableRowsOlderThanTheWindowOnly() {
        when(statements.findFileBackfillCandidates(anyInt(), any())).thenReturn(List.of());

        backfill(true, 10).run();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(statements).findFileBackfillCandidates(eq(10), cutoff.capture());
        assertEquals(CUTOFF, cutoff.getValue());
    }

    @Test
    void aRetriedStatementThatNowSucceedsBecomesAttached() {
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        select(candidate(id, user, "m1", "sha"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(conn, "m1", "sha")).thenThrow(new GmailEngineException(GmailError.NETWORK_ERROR, "x")).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.ATTACHED);

        StatementFileBackfill job = backfill(true, 10);
        job.run();
        job.run();

        ArgumentCaptor<String> outcomes = ArgumentCaptor.forClass(String.class);
        verify(statements, times(2)).markFileBackfill(eq(id), any(), outcomes.capture());
        assertEquals(List.of("ERROR", "ATTACHED"), outcomes.getAllValues());
    }

    @Test
    void theBatchSizeIsPassedToTheSelection() {
        when(statements.findFileBackfillCandidates(anyInt(), any())).thenReturn(List.of());

        backfill(true, 7).run();

        verify(statements).findFileBackfillCandidates(eq(7), any());
    }

    @Test
    void disabledDoesNothing() {
        backfill(false, 10).run();

        verifyNoInteractions(statements, connections, fetcher, attacher);
    }

    @Test
    void emptySelectionMakesNoGmailCallsAndLogsNothing() {
        when(statements.findFileBackfillCandidates(anyInt(), any())).thenReturn(List.of());

        backfill(true, 10).run();

        verifyNoInteractions(connections, fetcher, attacher);
        assertEquals("", summary());
    }

    @Test
    void aFailingSelectionNeverThrows() {
        when(statements.findFileBackfillCandidates(anyInt(), any())).thenThrow(new RuntimeException("db down"));

        backfill(true, 10).run();

        verifyNoInteractions(fetcher);
    }

    // ---- time budget

    /** A clock that moves forward a fixed step each time it is read. */
    private static Clock steppingClock(Duration step) {
        AtomicReference<Instant> t = new AtomicReference<>(NOW);
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return t.getAndUpdate(i -> i.plus(step)); }
        };
    }

    @Test
    void aRunStopsWhenItsTimeBudgetIsSpentAndLeavesTheRestUnstamped() {
        UUID user = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID(), s3 = UUID.randomUUID(), s4 = UUID.randomUUID();
        select(candidate(s1, user, "a1", "h1"), candidate(s2, user, "a2", "h2"), candidate(s3, user, "a3", "h3"), candidate(s4, user, "a4", "h4"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(any(), anyString(), anyString())).thenReturn(gone());

        // Every clock read moves 10s on; the budget is 25s. Reads: deadline(0) cutoff(10) then per statement a check
        // plus the stamp time: the first statements fit, a later one finds the budget spent.
        backfill(steppingClock(Duration.ofSeconds(10)), true, 10, 25).run();

        verify(fetcher, times(1)).fetch(eq(conn), eq("a1"), anyString());
        verify(fetcher, never()).fetch(eq(conn), eq("a4"), anyString());
        notStamped(s4);
        assertTrue(count("MAIL_GONE") >= 1 && count("MAIL_GONE") < 4);
    }

    @Test
    void aRunWithinItsBudgetProcessesEverything() {
        UUID user = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        select(candidate(s1, user, "a1", "h1"), candidate(s2, user, "a2", "h2"));
        GmailConnection conn = mailbox(user);
        when(fetcher.fetch(eq(conn), anyString(), anyString())).thenReturn(gone());

        backfill(true, 10).run();

        settledAs(s1, "MAIL_GONE");
        settledAs(s2, "MAIL_GONE");
    }

    // ---- summary

    @Test
    void oneRunLogsOneSummaryWithCountsPerOutcomeAndNoFileOrMailContent() {
        UUID u1 = UUID.randomUUID(), u2 = UUID.randomUUID();
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID();
        select(candidate(s1, u1, "a1", "h1"), candidate(s2, u2, "b1", "h2"));
        GmailConnection c1 = mailbox(u1);
        GmailConnection c2 = mailbox(u2);
        when(fetcher.fetch(c1, "a1", "h1")).thenReturn(found());
        when(attacher.attachDetailed(any(), any(), any(), any())).thenReturn(AttachResult.ATTACHED);
        when(fetcher.fetch(c2, "b1", "h2")).thenReturn(gone());

        backfill(true, 10).run();

        long summaries = logs.list.stream().filter(e -> e.getFormattedMessage().startsWith("Statement file back-fill {")).count();
        assertEquals(1, summaries);
        String line = summary();
        assertTrue(line.contains("ATTACHED=1") && line.contains("MAIL_GONE=1"));
        assertTrue(!line.contains("s.pdf") && !line.contains("a1"));
    }
}
