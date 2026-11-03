package com.financeos.gmail.accountdetect.job;

import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.user.User;
import com.financeos.gmail.accountdetect.BankDirectory;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.DetectionReport;
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
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
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
}
