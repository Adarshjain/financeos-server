package com.financeos.gmail.accountdetect.job;

import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.user.User;
import com.financeos.gmail.accountdetect.BankDirectory;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.DetectionReport;
import com.financeos.gmail.accountdetect.FeatureExtractor;
import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.Stability;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.scan.AccountDetectScan;
import com.financeos.gmail.accountdetect.scan.ScannedMail;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ACCOUNT_DETECT over made-up held-out mailboxes, with Gmail replaced by {@link FixtureMailSource}. */
class AccountDetectJobHandlerTest {

    private static final String BOX = "amex-hsbc-replace.json";

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private GmailConnection connection;
    private InMemoryAccountDetectStore store;
    private FixtureMailSource source;
    private final AtomicInteger opened = new AtomicInteger();
    private CapturingGuard guard;
    private AccountDetectTriggers triggers;
    private final List<Detection> detections = new ArrayList<>();
    private final List<DetectionContext> contexts = new ArrayList<>();
    private AccountDetectJobHandler handler;
    private JobExecutionContext ctx;
    private GmailConnectionRepository connections;

    static class CapturingGuard extends FirstScanGuard {
        final List<Runnable> later = new ArrayList<>();
        final List<Duration> delays = new ArrayList<>();

        @Override
        public void later(Runnable task, Duration delay) {
            later.add(task);
            delays.add(delay);
        }
    }

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId(userId);
        connection = new GmailConnection();
        connection.setId(connectionId);
        connection.setUser(user);
        connection.setIsConnected(true);
        connection.setIsPrimary(true);
        connections = mock(GmailConnectionRepository.class);
        when(connections.findById(connectionId)).thenReturn(Optional.of(connection));
        GmailSenderRepository senders = mock(GmailSenderRepository.class);
        when(senders.findByUserId(userId)).thenReturn(List.of());
        store = new InMemoryAccountDetectStore();
        guard = new CapturingGuard();
        triggers = mock(AccountDetectTriggers.class);
        MailSourceFactory factory = c -> {
            opened.incrementAndGet();
            return source;
        };
        DetectionSink sink = (u, d, c) -> {
            detections.add(d);
            contexts.add(c);
        };
        handler = new AccountDetectJobHandler(connections, senders, store, factory, guard, triggers, sink, mock(JdbcTemplate.class), 12, 50, 120);
        ctx = mock(JobExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
        when(ctx.payload(AccountDetectPayload.class)).thenReturn(new AccountDetectPayload(connectionId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run() throws Exception {
        return (Map<String, Object>) handler.execute(ctx);
    }

    private static List<String> tokens() {
        Set<String> t = new LinkedHashSet<>();
        for (String d : BankDirectory.allDomains()) t.add(d.toLowerCase(Locale.ROOT));
        return new ArrayList<>(t);
    }

    static String summary(Detection d) {
        return DetectionReport.table(d) + "\n" + DetectionReport.statementLinks(d) + "\n" + d.stats;
    }

    private Set<String> listedIds(FixtureMailSource s) {
        return new HashSet<>(s.list(tokens(), null).ids());
    }

    @Test
    void firstRunStoresEveryListedMailSetsTheMarkerAndDetectsLikeTheInMemoryPath() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        Set<String> listed = listedIds(source);
        source.listedAfter.clear();

        Map<String, Object> result = run();

        assertThat(result.get("status")).isEqualTo("DONE");
        assertThat(source.listedAfter).containsExactly(Instant.EPOCH);   // the whole mailbox
        List<AccountDetectFact> rows = store.rows(userId);
        assertThat(rows).hasSize(listed.size());
        AccountDetectState state = store.state(userId).orElseThrow();
        Instant newest = source.mails.stream().filter(m -> listed.contains(m.id())).map(FixtureMailSource.Mail::date).max(Comparator.naturalOrder()).orElseThrow();
        assertThat(state.getMarkerAt()).isEqualTo(newest);
        assertThat(state.getFirstScanDoneAt()).isNotNull();
        assertThat(state.getStatus()).isEqualTo(AccountDetectState.Status.IDLE);
        assertThat(state.getConnectionId()).isEqualTo(connectionId);
        assertThat(state.getMailsSeen()).isEqualTo(listed.size());
        assertThat(state.getKeySalt()).isNotBlank();
        assertThat(guard.isHeld()).isFalse();
        verify(ctx, org.mockito.Mockito.atLeastOnce()).progress(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any());

        // the detection over stored facts equals the in-memory pipeline over the same mail (same salt, same order)
        FixtureMailSource fresh = FixtureMailSource.of(BOX, m -> true);
        AccountDetectScan.Result mem = new AccountDetectScan(12, Integer.MAX_VALUE)
                .run(fresh, tokens(), null, Set.of(), List.of(), state.getKeySalt(), null, null, l -> {}, new HashMap<>());
        List<ScannedMail> ordered = new ArrayList<>(mem.mails());
        ordered.sort(Comparator.comparing(ScannedMail::internalDate).thenComparing(ScannedMail::messageId));
        List<MailDerived> derived = ordered.stream().map(ScannedMail::derived).toList();
        List<StatementContent> statements = ordered.stream().flatMap(m -> m.statements().stream()).toList();
        Detection expected = Stability.detectStable(derived, statements, 42L).detection();
        assertThat(detections).hasSize(1);
        assertThat(expected.entities).isNotEmpty();
        assertThat(summary(detections.get(0))).isEqualTo(summary(expected));
        assertThat(contexts.get(0).firstScan()).isTrue();
        assertThat(contexts.get(0).mailsStored()).isEqualTo(listed.size());
    }

    @Test
    void lockedStatementPdfsAreRecordedNeverOpenedAndFactsHoldNoMailText() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        run();

        Map<String, FixtureMailSource.Att> protectedAtts = new HashMap<>();
        for (FixtureMailSource.Mail m : source.mails)
            for (FixtureMailSource.Att a : m.attachments()) if (a.isProtected()) protectedAtts.put(a.attachmentId(), a);
        List<AccountDetectFact> locked = store.rows(userId).stream().filter(AccountDetectFact::getPdfLocked).toList();
        assertThat(locked).isNotEmpty();
        for (AccountDetectFact f : locked) {
            assertThat(f.getHasPdf()).isTrue();
            assertThat(f.getUnlockState()).isEqualTo(AccountDetectFact.UnlockState.LOCKED);
            assertThat(protectedAtts).containsKey(f.getPdfAttachmentId());
        }
        assertThat(store.rows(userId).stream().filter(f -> !f.getPdfLocked()))
                .allMatch(f -> f.getUnlockState() == AccountDetectFact.UnlockState.NONE);

        Map<String, FixtureMailSource.Mail> byId = new HashMap<>();
        for (FixtureMailSource.Mail m : source.mails) byId.put(m.id(), m);
        for (AccountDetectFact f : store.rows(userId)) {
            FixtureMailSource.Mail m = byId.get(f.getMessageId());
            assertThat(f.getFacts()).doesNotContain(m.subject());
            String firstSentence = m.body().split("[.\\n]")[0].trim();
            if (firstSentence.length() > 20) assertThat(f.getFacts()).doesNotContain(firstSentence);
        }
    }

    @Test
    void laterRunListsFromTheMarkerMinusTwoDaysSkipsStoredIdsAndAdvancesTheMarker() throws Exception {
        List<FixtureMailSource.Mail> all = FixtureMailSource.load(BOX);
        List<Instant> dates = all.stream().map(FixtureMailSource.Mail::date).sorted().toList();
        Instant cutoff = dates.get(dates.size() - 25);
        source = new FixtureMailSource(all.stream().filter(m -> m.date().isBefore(cutoff)).toList());
        run();
        AccountDetectState first = store.state(userId).orElseThrow();
        Instant marker1 = first.getMarkerAt();
        Instant firstDone = first.getFirstScanDoneAt();
        Set<String> stored1 = store.messageIds(userId);

        source = new FixtureMailSource(all);
        Set<String> listedAll = listedIds(source);
        source.listedAfter.clear();
        Map<String, Object> result = run();

        assertThat(result.get("status")).isEqualTo("DONE");
        assertThat(source.listedAfter).containsExactly(marker1.minus(Duration.ofDays(2)));
        assertThat(source.metadataRequested).isNotEmpty().doesNotContainAnyElementsOf(stored1);
        assertThat(store.messageIds(userId)).isEqualTo(listedAll);
        AccountDetectState second = store.state(userId).orElseThrow();
        assertThat(second.getMarkerAt()).isAfter(marker1)
                .isEqualTo(all.stream().filter(m -> listedAll.contains(m.id())).map(FixtureMailSource.Mail::date).max(Comparator.naturalOrder()).orElseThrow());
        assertThat(second.getFirstScanDoneAt()).isEqualTo(firstDone);
        assertThat(detections).hasSize(2);
        assertThat(contexts.get(1).firstScan()).isFalse();
        assertThat(contexts.get(1).mailsStored()).isEqualTo(listedAll.size());
        assertThat(contexts.get(1).newMails()).isEqualTo(listedAll.size() - stored1.size());
    }

    @Test
    void aCacheHitSkipsTheSecondLoadAndDetectButStillCallsTheSinkWithTheCachedDetection() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        run();
        AccountDetectState first = store.state(userId).orElseThrow();
        Instant marker1 = first.getMarkerAt();
        Instant lastRun1 = first.getLastRunAt();
        Set<String> stored1 = store.messageIds(userId);
        assertThat(detections).hasSize(1);

        Map<String, Object> result = run();   // same mailbox: every listed mail is already stored

        assertThat(result.get("status")).isEqualTo("DONE");
        assertThat(result.get("detection")).isEqualTo("SKIPPED_NOTHING_NEW");
        assertThat(result.get("newMails")).isEqualTo(0);
        assertThat(result.get("mailsStored")).isEqualTo(stored1.size());
        // no detector run: the sink got the very same detection again, for the same stored mails and no new ones
        assertThat(detections).hasSize(2);
        assertThat(detections.get(1)).isSameAs(detections.get(0));
        assertThat(contexts.get(1).mailsStored()).isEqualTo(contexts.get(0).mailsStored());
        assertThat(contexts.get(1).newMails()).isZero();
        assertThat(contexts.get(1).firstScan()).isFalse();
        AccountDetectState second = store.state(userId).orElseThrow();
        assertThat(second.getMarkerAt()).isEqualTo(marker1);
        assertThat(second.getLastRunAt()).isAfterOrEqualTo(lastRun1);
        assertThat(second.getStatus()).isEqualTo(AccountDetectState.Status.IDLE);
        assertThat(second.getFirstScanDoneAt()).isEqualTo(first.getFirstScanDoneAt());
        assertThat(store.messageIds(userId)).isEqualTo(stored1);
    }

    /** A handler like setUp's, but whose statements fingerprint query answers with the given count (one stamp per count). */
    private AccountDetectJobHandler handlerWithStatementsCount(java.util.concurrent.atomic.AtomicInteger count) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.doAnswer(inv -> {
            if (((String) inv.getArgument(0)).startsWith("SELECT COUNT(*)")) {
                java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                when(rs.getLong(1)).thenReturn((long) count.get());
                when(rs.getString(2)).thenReturn("c" + count.get());
                when(rs.getString(3)).thenReturn("u" + count.get());
                when(rs.getString(4)).thenReturn("a" + count.get());
                ((org.springframework.jdbc.core.RowCallbackHandler) inv.getArgument(1)).processRow(rs);
            }
            return null;
        }).when(jdbc).query(org.mockito.ArgumentMatchers.anyString(), any(org.springframework.jdbc.core.RowCallbackHandler.class),
                any(Object[].class));
        GmailSenderRepository senders = mock(GmailSenderRepository.class);
        when(senders.findByUserId(userId)).thenReturn(List.of());
        return new AccountDetectJobHandler(connections, senders, store, c -> source, guard, triggers,
                (u, d, c) -> { detections.add(d); contexts.add(c); }, jdbc, 12, 50, 120);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRunWithNoNewMailStillDetectsWhenTheAppParsedStatementsChangedAndSkipsWhenTheyDidNot() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        java.util.concurrent.atomic.AtomicInteger statements = new java.util.concurrent.atomic.AtomicInteger(2);
        handler = handlerWithStatementsCount(statements);
        handler.execute(ctx);
        assertThat(detections).hasSize(1);

        Map<String, Object> same = (Map<String, Object>) handler.execute(ctx);       // nothing new, same statements
        assertThat(same.get("detection")).isEqualTo("SKIPPED_NOTHING_NEW");
        assertThat(detections).hasSize(2);
        assertThat(detections.get(1)).isSameAs(detections.get(0));

        statements.set(3);                                                           // a statement was parsed meanwhile
        Map<String, Object> changed = (Map<String, Object>) handler.execute(ctx);
        assertThat(changed.get("detection")).isNull();
        assertThat(detections).hasSize(3);
        assertThat(detections.get(2)).isNotSameAs(detections.get(0));

        handler.execute(ctx);                                                        // and reuses the new one once recorded
        assertThat(detections).hasSize(4);
        assertThat(detections.get(3)).isSameAs(detections.get(2));
    }

    @Test
    void anIngestAlertLoadFailureFailsTheRunAndNeverSinksWithoutTheMerge() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new org.springframework.dao.TransientDataAccessResourceException("down")).when(jdbc)
                .query(org.mockito.ArgumentMatchers.contains("gmail_processed_messages"), any(org.springframework.jdbc.core.RowCallbackHandler.class), any(Object[].class));
        GmailSenderRepository senders = mock(GmailSenderRepository.class);
        when(senders.findByUserId(userId)).thenReturn(List.of());
        handler = new AccountDetectJobHandler(connections, senders, store, c -> source, guard, triggers,
                (u, d, c) -> { detections.add(d); contexts.add(c); }, jdbc, 12, 50, 120);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> handler.execute(ctx));
        assertThat(detections).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void aCacheMissRunsTheFullDetectionEvenWhenNothingIsNew() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        run();
        assertThat(detections).hasSize(1);
        // a restart: same stored facts and state, but no in-memory cache
        handler = handlerWithStatementsCount(new java.util.concurrent.atomic.AtomicInteger(0));

        Map<String, Object> result = (Map<String, Object>) handler.execute(ctx);

        assertThat(result.get("detection")).isNull();
        assertThat(detections).hasSize(2);
        assertThat(detections.get(1)).isNotSameAs(detections.get(0));
        assertThat(summary(detections.get(1))).isEqualTo(summary(detections.get(0)));
    }

    @Test
    void theNothingNewGuardNeverAppliesToAFirstScanOrWhenAMailWasStoredOrIsStillPartial() {
        AccountDetectScan.Result none = new AccountDetectScan.Result(List.of(), List.of(), Set.of(), 3, 3, List.of());
        AccountDetectJobHandler.Incomplete clean = new AccountDetectJobHandler.Incomplete(List.of(), 0);

        assertThat(AccountDetectJobHandler.nothingNew(false, none, List.of(), clean)).isTrue();
        assertThat(AccountDetectJobHandler.nothingNew(true, none, List.of(), clean)).isFalse();
        assertThat(AccountDetectJobHandler.nothingNew(false, none, List.of(ScannedMail.headersMissing("m1")), clean)).isFalse();
        assertThat(AccountDetectJobHandler.nothingNew(false, new AccountDetectScan.Result(List.of(ScannedMail.headersMissing("m1")), List.of(),
                Set.of(), 3, 2, List.of()), List.of(), clean)).isFalse();
        assertThat(AccountDetectJobHandler.nothingNew(false, none, List.of(),
                new AccountDetectJobHandler.Incomplete(List.of(Instant.EPOCH), 0))).isFalse();
        assertThat(AccountDetectJobHandler.nothingNew(false, none, List.of(),
                new AccountDetectJobHandler.Incomplete(List.of(), 1))).isFalse();
    }

    @Test
    void expiredGmailAccessPausesKeepsWhatIsStoredAndTheNextRunResumesTheFirstScan() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        source.failAuthAt = "full";
        Map<String, Object> result = run();

        assertThat(result.get("status")).isEqualTo("PAUSED_AUTH");
        AccountDetectState paused = store.state(userId).orElseThrow();
        assertThat(paused.getStatus()).isEqualTo(AccountDetectState.Status.PAUSED_AUTH);
        assertThat(paused.getLastError()).isEqualTo("AUTH_ERROR");
        assertThat(paused.getFirstScanDoneAt()).isNull();
        Set<String> kept = store.messageIds(userId);
        assertThat(kept).isNotEmpty();   // header-only mails were stored before the bodies failed
        assertThat(detections).isEmpty();
        assertThat(guard.isHeld()).isFalse();

        // reconnected: the first scan resumes, skipping what is stored
        source = FixtureMailSource.of(BOX, m -> true);
        Set<String> listed = listedIds(source);
        source.listedAfter.clear();
        result = run();
        assertThat(result.get("status")).isEqualTo("DONE");
        assertThat(source.listedAfter).containsExactly(Instant.EPOCH);
        assertThat(source.metadataRequested).doesNotContainAnyElementsOf(kept);
        assertThat(store.messageIds(userId)).isEqualTo(listed);
        assertThat(store.state(userId).orElseThrow().getFirstScanDoneAt()).isNotNull();
    }

    @Test
    void aMailboxFlaggedAsAuthFailedPausesWithoutReadingGmail() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        connection.setAuthFailedAt(Instant.now());

        Map<String, Object> result = run();

        assertThat(result.get("status")).isEqualTo("PAUSED_AUTH");
        assertThat(opened.get()).isZero();
        assertThat(store.state(userId).orElseThrow().getStatus()).isEqualTo(AccountDetectState.Status.PAUSED_AUTH);
    }

    @Test
    void aFirstScanWaitsWhileAnotherFirstScanRunsAndIsRequeuedAfterADelay() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        UUID other = UUID.randomUUID();
        assertThat(guard.tryAcquire(other)).isTrue();

        Map<String, Object> result = run();

        assertThat(result.get("status")).isEqualTo("DEFERRED");
        assertThat(opened.get()).isZero();
        assertThat(store.rows(userId)).isEmpty();
        assertThat(guard.later).hasSize(1);
        assertThat(guard.delays.get(0)).isEqualTo(Duration.ofSeconds(120));
        guard.later.get(0).run();
        verify(triggers).enqueue(userId, connectionId);

        // once the other scan is done, this user's first scan takes the guard
        guard.release(other);
        assertThat(run().get("status")).isEqualTo("DONE");
    }

    @Test
    void rescansAreNotHeldBackByARunningFirstScan() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        run();
        assertThat(guard.tryAcquire(UUID.randomUUID())).isTrue();

        source = FixtureMailSource.of(BOX, m -> true);
        assertThat(run().get("status")).isEqualTo("DONE");
        assertThat(guard.later).isEmpty();
    }

    @Test
    void firstScanGuardAdmitsOneUserAtATime() {
        FirstScanGuard g = new FirstScanGuard();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThat(g.tryAcquire(a)).isTrue();
        assertThat(g.tryAcquire(b)).isFalse();
        g.release(b);
        assertThat(g.tryAcquire(b)).isFalse();
        g.release(a);
        assertThat(g.tryAcquire(b)).isTrue();
    }

    @Test
    void aDisconnectedMailboxIsSkipped() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        connection.setIsConnected(false);
        assertThat(run().get("status")).isEqualTo("SKIPPED_DISCONNECTED");
        assertThat(opened.get()).isZero();
    }

    // ---- partial reads: a mail is never stored as complete, nor passed by the marker, while a read of it failed

    private FactsCodec.Facts facts(String messageId) {
        return store.rows(userId).stream().filter(f -> f.getMessageId().equals(messageId)).findFirst()
                .map(f -> FactsCodec.decode(f.getFacts())).orElseThrow();
    }

    private static AccountDetectScan.Result memScan(FixtureMailSource src, String salt, int alertSample) throws Exception {
        return new AccountDetectScan(alertSample, Integer.MAX_VALUE).run(src, tokens(), null, Set.of(), List.of(), salt, null, null, l -> {}, new HashMap<>());
    }

    private static Detection inMemoryDetection(AccountDetectScan.Result mem) {
        List<ScannedMail> ordered = new ArrayList<>(mem.mails());
        ordered.sort(Comparator.comparing(ScannedMail::internalDate).thenComparing(ScannedMail::messageId));
        return Stability.detectStable(ordered.stream().map(ScannedMail::derived).toList(),
                ordered.stream().flatMap(m -> m.statements().stream()).toList(), 42L).detection();
    }

    /** A statement mail of the box whose open PDF yields statement content (the middle one by date). */
    private static ScannedMail statementMailWithOpenPdf() throws Exception {
        List<ScannedMail> c = memScan(FixtureMailSource.of(BOX, m -> true), FeatureExtractor.newSalt(), 12).mails().stream()
                .filter(m -> m.bodyFetched() && !m.statements().isEmpty())
                .sorted(Comparator.comparing(ScannedMail::internalDate)).toList();
        assertThat(c).isNotEmpty();
        return c.get(c.size() / 2);
    }

    @Test
    void aMailWhoseBodyCouldNotBeReadIsStoredPartialKeepsTheMarkerBeforeItAndIsReadAgainNextRun() throws Exception {
        ScannedMail x = statementMailWithOpenPdf();
        source = FixtureMailSource.of(BOX, m -> true);
        Set<String> listed = listedIds(source);
        source.failFull.add(x.messageId());

        assertThat(run().get("status")).isEqualTo("DONE");
        AccountDetectState s1 = store.state(userId).orElseThrow();
        assertThat(s1.getFirstScanDoneAt()).isNull();   // not done while a mail is partial
        assertThat(s1.getMarkerAt()).isEqualTo(x.internalDate().minusMillis(1));
        FactsCodec.Facts partial = facts(x.messageId());
        assertThat(partial.pending()).isTrue();
        assertThat(partial.incompleteRuns()).isEqualTo(1);
        assertThat(partial.bodyFetched()).isFalse();
        assertThat(store.messageIds(userId)).isEqualTo(listed);

        // Gmail is fine again: the first scan resumes, reads only the partial mail, and finishes
        source = FixtureMailSource.of(BOX, m -> true);
        source.listedAfter.clear();
        assertThat(run().get("status")).isEqualTo("DONE");
        assertThat(source.listedAfter).containsExactly(Instant.EPOCH);
        assertThat(source.metadataRequested).containsExactly(x.messageId());
        assertThat(source.fullRequested).containsExactly(x.messageId());
        FactsCodec.Facts complete = facts(x.messageId());
        assertThat(complete.incompleteRuns()).isZero();
        assertThat(complete.bodyFetched()).isTrue();
        assertThat(complete.statements()).isNotEmpty();
        assertThat(store.rows(userId)).hasSize(listed.size());
        AccountDetectState s2 = store.state(userId).orElseThrow();
        assertThat(s2.getFirstScanDoneAt()).isNotNull();
        Instant newest = source.mails.stream().filter(m -> listed.contains(m.id())).map(FixtureMailSource.Mail::date).max(Comparator.naturalOrder()).orElseThrow();
        assertThat(s2.getMarkerAt()).isEqualTo(newest);

        // and the detection equals a scan that never failed
        Detection expected = inMemoryDetection(memScan(FixtureMailSource.of(BOX, m -> true), s2.getKeySalt(), 12));
        assertThat(summary(detections.get(1))).isEqualTo(summary(expected));
    }

    @Test
    void theSecondLoadReusesTheStoredFactsAndEqualsAFreshFullLoadAfterNewMailsAndAReplacedPartial() throws Exception {
        ScannedMail x = statementMailWithOpenPdf();
        List<FixtureMailSource.Mail> all = FixtureMailSource.load(BOX);
        List<Instant> dates = all.stream().map(FixtureMailSource.Mail::date).sorted().toList();
        Instant cutoff = dates.get(dates.size() - 25);
        source = new FixtureMailSource(all.stream().filter(m -> m.date().isBefore(cutoff)).toList());
        source.failFull.add(x.messageId());
        run();
        assertThat(facts(x.messageId()).pending()).isTrue();

        // second run: 25 newer mails are stored and the partial one is replaced
        source = new FixtureMailSource(all);
        store.factsLoads = 0;
        assertThat(run().get("status")).isEqualTo("DONE");
        assertThat(store.factsLoads).isEqualTo(1);   // "0 load stored" only; the second load reads just the written ids
        assertThat(source.fullRequested).contains(x.messageId());
        assertThat(facts(x.messageId()).pending()).isFalse();

        List<AccountDetectStore.StoredFact> fresh = store.facts(userId);
        List<AccountDetectStore.StoredFact> stored = fresh.stream().filter(f -> !source.metadataRequested.contains(f.messageId()) && !f.messageId().equals(x.messageId())).toList();
        assertThat(stored).isNotEmpty().hasSizeLessThan(fresh.size());
        // merged = the old rows with the written ids replaced or added, in the store's order
        List<AccountDetectStore.StoredFact> written = fresh.stream().filter(f -> !stored.contains(f)).toList();
        assertThat(AccountDetectJobHandler.mergeWritten(stored, written))
                .extracting(AccountDetectStore.StoredFact::messageId).containsExactlyElementsOf(fresh.stream().map(AccountDetectStore.StoredFact::messageId).toList());
        // and the detection equals one over a fresh full load
        assertThat(summary(detections.get(1))).isEqualTo(summary(AccountDetectJobHandler.detect(fresh, List.of()).detection()));
    }

    @Test
    void mergeWrittenReplacesByMessageIdAndSortsNullDatesFirstThenById() {
        Instant d1 = Instant.parse("2025-01-01T00:00:00Z");
        Instant d2 = Instant.parse("2025-02-01T00:00:00Z");
        FactsCodec.Facts f = FactsCodec.decode(FactsCodec.encode(null, List.of(), false, null, 0));
        List<AccountDetectStore.StoredFact> stored = List.of(new AccountDetectStore.StoredFact("b", d2, f), new AccountDetectStore.StoredFact("a", d1, f),
                new AccountDetectStore.StoredFact("z", null, f));
        List<AccountDetectStore.StoredFact> written = List.of(new AccountDetectStore.StoredFact("a", d2, f), new AccountDetectStore.StoredFact("c", null, f));
        assertThat(AccountDetectJobHandler.mergeWritten(stored, written)).extracting(AccountDetectStore.StoredFact::messageId)
                .containsExactly("c", "z", "a", "b");
    }

    @Test
    void aMailWhoseHeadersCouldNotBeReadHoldsTheMarkerUntilARunReadsIt() throws Exception {
        List<FixtureMailSource.Mail> all = FixtureMailSource.load(BOX);
        List<Instant> dates = all.stream().map(FixtureMailSource.Mail::date).sorted().toList();
        Instant cutoff = dates.get(dates.size() - 25);
        source = new FixtureMailSource(all.stream().filter(m -> m.date().isBefore(cutoff)).toList());
        run();
        Instant marker1 = store.state(userId).orElseThrow().getMarkerAt();

        source = new FixtureMailSource(all);
        Set<String> listedAll = listedIds(source);
        FixtureMailSource.Mail newestMail = all.stream().filter(m -> listedAll.contains(m.id()))
                .max(Comparator.comparing(FixtureMailSource.Mail::date)).orElseThrow();
        source.failMetadata.add(newestMail.id());
        assertThat(run().get("status")).isEqualTo("DONE");
        assertThat(store.state(userId).orElseThrow().getMarkerAt()).isEqualTo(marker1);   // no date known: the marker stays
        FactsCodec.Facts missing = facts(newestMail.id());
        assertThat(missing.derived()).isNull();
        assertThat(missing.pending()).isTrue();

        source = new FixtureMailSource(all);
        source.listedAfter.clear();
        assertThat(run().get("status")).isEqualTo("DONE");
        assertThat(source.listedAfter).containsExactly(marker1.minus(Duration.ofDays(2)));
        assertThat(source.metadataRequested).containsExactly(newestMail.id());
        assertThat(facts(newestMail.id()).derived()).isNotNull();
        assertThat(facts(newestMail.id()).incompleteRuns()).isZero();
        assertThat(store.state(userId).orElseThrow().getMarkerAt()).isEqualTo(newestMail.date());
        assertThat(store.messageIds(userId)).isEqualTo(listedAll);
    }

    @Test
    void aStatementPdfThatFailsThreeRunsInARowIsKeptFlaggedAndNoLongerHoldsTheMarker() throws Exception {
        ScannedMail x = statementMailWithOpenPdf();
        for (int runNo = 1; runNo <= 3; runNo++) {
            source = FixtureMailSource.of(BOX, m -> true);
            source.failAttachment.add(x.messageId());
            assertThat(run().get("status")).isEqualTo("DONE");
            assertThat(facts(x.messageId()).incompleteRuns()).as("run " + runNo).isEqualTo(runNo);
            AccountDetectState st = store.state(userId).orElseThrow();
            if (runNo < 3) {
                assertThat(facts(x.messageId()).pending()).isTrue();
                assertThat(st.getFirstScanDoneAt()).isNull();
                assertThat(st.getMarkerAt()).isEqualTo(x.internalDate().minusMillis(1));
            } else {
                assertThat(facts(x.messageId()).givenUp()).isTrue();
                assertThat(facts(x.messageId()).bodyFetched()).isTrue();
                assertThat(st.getFirstScanDoneAt()).isNotNull();
                assertThat(st.getMarkerAt()).isAfter(x.internalDate());
            }
        }
        assertThat(store.rows(userId).stream().filter(f -> f.getMessageId().equals(x.messageId()))).hasSize(1);

        // given up: later runs leave it alone
        source = FixtureMailSource.of(BOX, m -> true);
        run();
        assertThat(source.metadataRequested).doesNotContain(x.messageId());
        assertThat(facts(x.messageId()).givenUp()).isTrue();
    }

    @Test
    void disconnectingDuringTheScanStopsItWithoutSavingAnythingMore() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        source.beforeFull = () -> {
            // what the disconnect endpoint does: connection off, state and facts deleted
            connection.setIsConnected(false);
            store.deleteForUser(userId);
        };

        assertThat(run().get("status")).isEqualTo("ABORTED_DISCONNECTED");
        assertThat(store.state(userId)).isEmpty();
        assertThat(store.rows(userId)).isEmpty();
        assertThat(detections).isEmpty();
        assertThat(guard.isHeld()).isFalse();
    }

    @Test
    void stateDeletedDuringTheScanAlsoStopsIt() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        source.beforeFull = () -> store.deleteForUser(userId);

        assertThat(run().get("status")).isEqualTo("ABORTED_DISCONNECTED");
        assertThat(store.state(userId)).isEmpty();
        assertThat(store.rows(userId)).isEmpty();
        assertThat(detections).isEmpty();
    }

    // ---- role votes: one header-level vote in first scans and later runs

    /**
     * Every held-out dev mailbox: in a first scan of the older mail followed by an incremental run, the incremental run
     * gives each new mail the cluster role one scan of all the mail gives it (both count header votes only), and the
     * detection over the stored facts equals the same two scans done in memory.
     */
    @Test
    void aFirstScanThenAnIncrementalRunVotesLikeOneScanAndDetectsLikeTheInMemoryPath() throws Exception {
        List<java.nio.file.Path> files;
        try (var st = java.nio.file.Files.list(FixtureMailSource.DEV)) {
            files = st.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        int split = 0;
        for (java.nio.file.Path f : files) {
            String box = f.getFileName().toString();
            List<FixtureMailSource.Mail> all = FixtureMailSource.load(box);
            List<Instant> dates = all.stream().map(FixtureMailSource.Mail::date).distinct().sorted().toList();
            if (dates.size() < 6) continue;
            Instant cutoff = dates.get(dates.size() * 2 / 3);
            List<FixtureMailSource.Mail> older = all.stream().filter(m -> m.date().isBefore(cutoff)).toList();
            store = new InMemoryAccountDetectStore();
            detections.clear();
            handler = new AccountDetectJobHandler(connections, mock(GmailSenderRepository.class), store, c -> source, new FirstScanGuard(),
                    triggers, (u, d, c) -> detections.add(d), mock(JdbcTemplate.class), 12, 50, 120);

            source = new FixtureMailSource(older);
            run();
            Set<String> firstIds = store.messageIds(userId);
            source = new FixtureMailSource(all);
            assertThat(run().get("status")).as(box).isEqualTo("DONE");
            if (source.metadataRequested.isEmpty()) continue;
            split++;
            String salt = store.state(userId).orElseThrow().getKeySalt();

            // roles of the incremental run's mails = roles of one scan of everything
            AccountDetectScan.Result one = memScan(new FixtureMailSource(all), salt, 12);
            Map<String, String> roleOne = new HashMap<>();
            for (ScannedMail m : one.mails()) if (!firstIds.contains(m.messageId())) roleOne.put(m.messageId(), String.valueOf(m.role()));
            Map<String, String> roleSplit = new HashMap<>();
            for (AccountDetectFact r : store.rows(userId)) if (!firstIds.contains(r.getMessageId())) roleSplit.put(r.getMessageId(), r.getRole());
            assertThat(roleSplit).as(box).isNotEmpty().isEqualTo(roleOne);

            // facts -> detector equals the same two scans in memory
            AccountDetectScan.Result mem1 = memScan(new FixtureMailSource(older), salt, 12);
            List<AccountDetectScan.Prior> prior = mem1.mails().stream()
                    .map(m -> new AccountDetectScan.Prior(m.derived(), m.bodyFetched(), m.headerVote())).toList();
            Set<String> known = new HashSet<>();
            for (ScannedMail m : mem1.mails()) known.add(m.messageId());
            AccountDetectScan.Result mem2 = new AccountDetectScan(12, Integer.MAX_VALUE).run(new FixtureMailSource(all), tokens(), null, known,
                    prior, salt, null, null, l -> {}, new HashMap<>());
            List<ScannedMail> both = new ArrayList<>(mem1.mails());
            both.addAll(mem2.mails());
            assertThat(summary(detections.get(1))).as(box).isEqualTo(summary(inMemoryDetection(
                    new AccountDetectScan.Result(both, List.of(), Set.of(), 0, 0, List.of()))));
        }
        assertThat(split).isGreaterThan(5);
    }

    /**
     * A stored mail whose body-read vote differs from its header vote still votes with its header vote, so an
     * incremental run's cluster roles equal one scan's even when every stored body voted otherwise.
     */
    @Test
    void storedMailsVoteWithTheirHeaderVoteNotTheirBodyVote() throws Exception {
        String salt = FeatureExtractor.newSalt();
        List<FixtureMailSource.Mail> all = FixtureMailSource.load(BOX);
        List<Instant> dates = all.stream().map(FixtureMailSource.Mail::date).distinct().sorted().toList();
        Instant cutoff = dates.get(dates.size() * 2 / 3);
        AccountDetectScan.Result one = memScan(new FixtureMailSource(all), salt, 12);
        AccountDetectScan.Result older = memScan(new FixtureMailSource(all.stream().filter(m -> m.date().isBefore(cutoff)).toList()), salt, 12);

        // every stored mail's body "voted" MARKETING; its header vote is what it voted in the first scan
        List<AccountDetectScan.Prior> prior = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (ScannedMail m : older.mails()) {
            MailDerived d = m.derived();
            MailDerived bodyVote = new MailDerived(d.bank(), d.clusterId(), com.financeos.gmail.accountdetect.Vocab.Role.MARKETING, d.date(),
                    d.clusterKind(), d.slots(), d.variants(), d.events(), d.appHits(), d.layout(), d.limits());
            prior.add(new AccountDetectScan.Prior(bodyVote, m.bodyFetched(), m.headerVote()));
            known.add(m.messageId());
        }
        AccountDetectScan.Result later = new AccountDetectScan(12, Integer.MAX_VALUE).run(new FixtureMailSource(all), tokens(), null, known,
                prior, salt, null, null, l -> {}, new HashMap<>());

        Map<String, Object> roleOne = new HashMap<>();
        for (ScannedMail m : one.mails()) if (!known.contains(m.messageId())) roleOne.put(m.messageId(), m.role());
        Map<String, Object> roleLater = new HashMap<>();
        for (ScannedMail m : later.mails()) roleLater.put(m.messageId(), m.role());
        assertThat(roleLater).isNotEmpty().isEqualTo(roleOne);
    }

    // ---- which mailbox

    @Test
    void onlyTheScannedMailboxRunsWhileThePrimaryIsConnected() throws Exception {
        source = FixtureMailSource.of(BOX, m -> true);
        connection.setIsPrimary(false);
        GmailConnection primary = new GmailConnection();
        primary.setId(UUID.randomUUID());
        primary.setUser(connection.getUser());
        primary.setIsConnected(true);
        primary.setIsPrimary(true);
        when(connections.findByUserId(userId)).thenReturn(List.of(primary, connection));

        assertThat(run().get("status")).isEqualTo("SKIPPED_NOT_SCANNED_MAILBOX");
        assertThat(opened.get()).isZero();

        // the primary is disconnected: the other connected mailbox is the one scanned
        primary.setIsConnected(false);
        assertThat(run().get("status")).isEqualTo("DONE");
        assertThat(store.state(userId).orElseThrow().getConnectionId()).isEqualTo(connectionId);
    }
}
