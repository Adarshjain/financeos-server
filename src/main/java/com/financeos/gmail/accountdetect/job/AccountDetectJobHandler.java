package com.financeos.gmail.accountdetect.job;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.job.JobCancelledException;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import com.financeos.gmail.accountdetect.BankDirectory;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.FeatureExtractor;
import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.Stability;
import com.financeos.gmail.accountdetect.HolderCheck;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.scan.AccountDetectScan;
import com.financeos.gmail.accountdetect.scan.DbStatements;
import com.financeos.gmail.accountdetect.scan.MailSource;
import com.financeos.gmail.accountdetect.scan.ScannedMail;
import com.financeos.gmail.accountdetect.unlock.PasswordRuleParser;
import com.financeos.gmail.accountdetect.unlock.StatementUnlocker;
import com.financeos.gmail.accountdetect.unlock.UnlockProfile;
import com.financeos.gmail.accountdetect.unlock.UnlockProfileLoader;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSender;
import com.financeos.gmail.ingest.GmailSenderRepository;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ACCOUNT_DETECT: reads the user's bank-sender mail with the v3 detector's pipeline ({@link AccountDetectScan}),
 * stores per-mail derived facts, then runs the detector over all stored facts plus app-parsed statements and hands the
 * detection to the {@link DetectionSink}.
 *
 * <ul>
 *   <li>First scan (no state, or never finished): the whole mailbox, resuming past already stored ids; one at a time
 *       per server ({@link FirstScanGuard}), others are re-queued after a delay.</li>
 *   <li>Later runs: mail newer than the marker minus two days, skipping stored ids; then the marker advances.</li>
 *   <li>A mail whose headers, wanted body or statement PDF could not be read is stored as partial and read again by
 *       the next run: the marker stays before it and a first scan is not done while one is partial. After
 *       {@link FactsCodec#GIVE_UP_AFTER_RUNS} failed runs in a row it is kept as it is (flagged) and the marker moves.</li>
 *   <li>The mailbox disconnected mid-run (connection off or state deleted): the run stops without saving anything.</li>
 *   <li>Gmail access expired: state PAUSED_AUTH, everything stored is kept, the job ends cleanly (no retry).</li>
 *   <li>Protected statement PDFs are tried with passwords built from the user's name and birth date by rule (the mail's own
 *       hint, the researched table, saved statement passwords); opened ones are read like open PDFs, the rest are recorded
 *       LOCKED (nothing to try) or FAILED. A retry job ({@code unlockRetry}) tries the still closed ones again.</li>
 * </ul>
 * Logs carry counts, timings, statuses and bank ids only.
 */
@Component
public class AccountDetectJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountDetectJobHandler.class);
    static final Duration MARKER_OVERLAP = Duration.ofDays(2);
    static final long STABILITY_SEED = 42L;

    private final GmailConnectionRepository connectionRepository;
    private final GmailSenderRepository senderRepository;
    private final AccountDetectStore store;
    private final MailSourceFactory sources;
    private final FirstScanGuard guard;
    private final AccountDetectTriggers triggers;
    private final DetectionSink sink;
    private final JdbcTemplate jdbc;
    private final int alertSample;
    private final int chunkSize;
    private final Duration firstScanRetryDelay;
    private final UnlockProfileLoader profiles;
    StatementUnlocker unlocker = StatementUnlocker.bundled();   // package-visible so a test can swap it

    /** Without a profile loader no protected PDF is ever tried (they stay LOCKED). */
    public AccountDetectJobHandler(GmailConnectionRepository connectionRepository, GmailSenderRepository senderRepository,
                                   AccountDetectStore store, MailSourceFactory sources, FirstScanGuard guard,
                                   AccountDetectTriggers triggers, DetectionSink sink, JdbcTemplate jdbc,
                                   int alertSample, int chunkSize, long firstScanRetrySeconds) {
        this(connectionRepository, senderRepository, store, sources, guard, triggers, sink, jdbc, null, alertSample, chunkSize, firstScanRetrySeconds);
    }

    @Autowired
    public AccountDetectJobHandler(GmailConnectionRepository connectionRepository, GmailSenderRepository senderRepository,
                                   AccountDetectStore store, MailSourceFactory sources, FirstScanGuard guard,
                                   AccountDetectTriggers triggers, DetectionSink sink, JdbcTemplate jdbc,
                                   UnlockProfileLoader profiles,
                                   @Value("${financeos.account-detect.alert-sample:12}") int alertSample,
                                   @Value("${financeos.account-detect.chunk-size:200}") int chunkSize,
                                   @Value("${financeos.account-detect.first-scan-retry-seconds:120}") long firstScanRetrySeconds) {
        this.connectionRepository = connectionRepository;
        this.senderRepository = senderRepository;
        this.store = store;
        this.sources = sources;
        this.guard = guard;
        this.triggers = triggers;
        this.sink = sink;
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.alertSample = alertSample;
        this.chunkSize = chunkSize;
        this.firstScanRetryDelay = Duration.ofSeconds(firstScanRetrySeconds);
    }

    @Override
    public JobType type() {
        return JobType.ACCOUNT_DETECT;
    }

    @Override
    public Object execute(JobExecutionContext ctx) throws Exception {
        Object result = executeRun(ctx);
        // a retry job that was capped is this run's one capped pass: no second capped pass in its tail
        if (profiles != null && result instanceof Map<?, ?> m && "DONE".equals(m.get("status")) && !Boolean.TRUE.equals(m.get("capped"))) {
            try {
                retryAskedFor(ctx.getUserId(), ctx.payload(AccountDetectPayload.class).connectionId());
            } catch (Exception e) {
                // the scan itself finished: a failed retry only leaves its facts untried and never fails the job
                log.warn("Account detect unlock retry after the run failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * After a finished run of any kind: honour a retry asked for meanwhile (a trigger whose enqueue was deduped), i.e. facts not
     * tried at the current generation. At most two passes, and the second only when the first was not capped, so one run never
     * does more than one capped pass. A trigger arriving mid-pass leaves facts below the new generation for the second check.
     */
    private void retryAskedFor(UUID userId, UUID connectionId) throws Exception {
        for (int pass = 0; pass < 2; pass++) {
            AccountDetectState state = store.state(userId).orElse(null);
            GmailConnection connection = state == null ? null : connectionRepository.findById(connectionId).orElse(null);
            if (connection == null || !Boolean.TRUE.equals(connection.getIsConnected()) || connection.getAuthFailedAt() != null) return;
            if (!store.retryPending(userId)) return;
            Map<String, Object> r = retryUnlock(userId, connection);
            if (!"DONE".equals(r.get("status")) || Boolean.TRUE.equals(r.get("capped"))) return;
        }
    }

    private Object executeRun(JobExecutionContext ctx) throws Exception {
        UUID userId = ctx.getUserId();
        AccountDetectPayload payload = ctx.payload(AccountDetectPayload.class);
        if (userId == null || payload == null || payload.connectionId() == null) {
            throw new ValidationException("ACCOUNT_DETECT needs a user and a Gmail connection");
        }
        GmailConnection connection = connectionRepository.findById(payload.connectionId()).orElse(null);
        if (connection == null) {
            return result("SKIPPED_NO_CONNECTION", 0, 0, 0);
        }
        if (!userId.equals(connection.getUser().getId())) {
            throw new ValidationException("SECURITY_MISMATCH: Gmail connection does not belong to user " + userId);
        }
        if (!Boolean.TRUE.equals(connection.getIsConnected())) {
            return result("SKIPPED_DISCONNECTED", 0, 0, 0);
        }
        GmailConnection target = AccountDetectTriggers.scanTarget(connectionRepository.findByUserId(userId));
        if (target != null && !connection.getId().equals(target.getId())) {
            // the scan reads one mailbox: the connected primary, else the oldest connected one
            return result("SKIPPED_NOT_SCANNED_MAILBOX", 0, 0, 0);
        }

        if (payload.unlockRetry()) {
            if (connection.getAuthFailedAt() != null) return result("SKIPPED_PAUSED_AUTH", 0, 0, 0);
            return retryUnlock(userId, connection);
        }

        AccountDetectState state = store.state(userId).orElseGet(() -> new AccountDetectState(userId));
        if (state.getConnectionId() != null && !state.getConnectionId().equals(connection.getId())) {
            // another mailbox became the scanned one: its facts do not carry over
            store.deleteForUser(userId);
            state = new AccountDetectState(userId);
        }
        if (connection.getAuthFailedAt() != null) {
            pausedForAuth(state, connection.getId());
            return result(AccountDetectState.Status.PAUSED_AUTH.name(), 0, 0, 0);
        }

        boolean firstScan = state.getFirstScanDoneAt() == null;
        if (firstScan && !guard.tryAcquire(userId)) {
            UUID connectionId = connection.getId();
            guard.later(() -> {
                try {
                    triggers.enqueue(userId, connectionId);
                } catch (Exception e) {
                    log.warn("Account detect re-queue failed: {}", e.getClass().getSimpleName());
                }
            }, firstScanRetryDelay);
            log.info("Account detect first scan deferred: another first scan is running, retry in {}s", firstScanRetryDelay.toSeconds());
            return result("DEFERRED", 0, 0, 0);
        }
        try {
            return run(ctx, userId, connection, state, firstScan);
        } finally {
            if (firstScan) guard.release(userId);
        }
    }

    private Object run(JobExecutionContext ctx, UUID userId, GmailConnection connection, AccountDetectState initial, boolean firstScan) throws Exception {
        long started = System.currentTimeMillis();
        initial.setConnectionId(connection.getId());
        initial.setStatus(AccountDetectState.Status.RUNNING);
        initial.setLastError(null);
        initial.setMailsSeen(0);
        initial.setMailsTotal(0);
        if (initial.getKeySalt() == null) initial.setKeySalt(FeatureExtractor.newSalt());
        AccountDetectState[] state = {store.save(initial)};
        String salt = state[0].getKeySalt();

        List<AccountDetectStore.StoredFact> stored = store.facts(userId);
        Set<String> known = new HashSet<>();
        List<AccountDetectScan.Prior> prior = new ArrayList<>();
        // partial mails are listed and read again (their rows get replaced); their failed runs so far
        Map<String, Integer> partialRuns = new HashMap<>();
        for (AccountDetectStore.StoredFact f : stored) {
            if (f.facts().pending()) {
                partialRuns.put(f.messageId(), f.facts().incompleteRuns());
                continue;
            }
            known.add(f.messageId());
            if (f.facts().derived() != null) {
                prior.add(new AccountDetectScan.Prior(f.facts().derived(), f.facts().bodyFetched(), f.facts().headerVote()));
            }
        }
        Instant after = firstScan || state[0].getMarkerAt() == null ? null : state[0].getMarkerAt().minus(MARKER_OVERLAP);
        UUID connectionId = connection.getId();

        Map<String, Long> timings = new LinkedHashMap<>();
        AccountDetectScan.Result scan;
        try {
            MailSource source = sources.open(connection);
            AccountDetectScan scanner = new AccountDetectScan(alertSample, chunkSize);
            if (profiles != null) {
                UnlockProfile profile = profileOf(userId);
                scanner.withUnlocker((pdf, target) -> unlocker.unlock(pdf, target, profile));
                scanner.withHolderCheck(HolderCheck.forName(profile.displayName()));
            }
            scan = scanner.run(source, tokens(userId), after, known, prior, salt,
                    chunk -> {
                        requireStillScanned(userId, connectionId);
                        store.saveAll(userId, chunk, partialRuns);
                    },
                    (seen, total) -> {
                        requireStillScanned(userId, connectionId);
                        state[0].setMailsSeen(seen);
                        state[0].setMailsTotal(total);
                        state[0] = store.save(state[0]);
                        ctx.progress(seen, total, "Reading bank mail");
                        ctx.checkCancelled();
                    },
                    line -> log.debug("Account detect {}", line), timings);
        } catch (MailboxGone e) {
            return mailboxGone();
        } catch (GmailEngineException e) {
            if (e.getErrorType() == GmailError.AUTH_ERROR) {
                if (!stillScanned(userId, connectionId)) return mailboxGone();
                pausedForAuth(state[0], connection.getId());
                return result(AccountDetectState.Status.PAUSED_AUTH.name(), 0, stored.size(), 0);
            }
            if (stillScanned(userId, connectionId)) failed(state[0], "GMAIL_" + e.getErrorType().name());
            throw e;
        } catch (JobCancelledException e) {
            if (stillScanned(userId, connectionId)) {
                state[0].setStatus(AccountDetectState.Status.IDLE);
                store.save(state[0]);
            }
            throw e;
        } catch (Exception e) {
            if (stillScanned(userId, connectionId)) failed(state[0], e.getClass().getSimpleName());
            throw e;
        }

        // listed mails whose headers could not be read: stored without features, read again next run
        List<ScannedMail> headersMissing = scan.headersMissing().stream().map(ScannedMail::headersMissing).toList();
        if (!headersMissing.isEmpty()) {
            if (!stillScanned(userId, connectionId)) return mailboxGone();
            store.saveAll(userId, headersMissing, partialRuns);
        }
        Incomplete incomplete = incomplete(scan, headersMissing, partialRuns);

        AccountDetectState s = state[0];
        List<AccountDetectStore.StoredFact> all;
        try {
            all = store.facts(userId);
        } catch (Exception e) {
            if (stillScanned(userId, connectionId)) failed(s, "DETECT_" + e.getClass().getSimpleName());
            throw e;
        }
        // the newest stored mail (earlier runs may have stored newer mail while a partial one held the marker back)
        Instant newest = scan.newest();
        for (AccountDetectStore.StoredFact f : all) {
            if (f.internalDate() != null && !f.facts().pending() && (newest == null || f.internalDate().isAfter(newest))) newest = f.internalDate();
        }
        s.setMarkerAt(nextMarker(s.getMarkerAt(), newest, incomplete));
        Instant now = AppTime.nowInstant();
        boolean firstScanCompleted = firstScan && incomplete.partial().isEmpty();
        if (firstScanCompleted) s.setFirstScanDoneAt(now);
        s.setLastRunAt(now);
        s.setMailsSeen(scan.mails().size());
        s.setMailsTotal(scan.mails().size());

        Detection detection;
        int mailsStored;
        int statementCount;
        try {
            Detected d = detect(all, DbStatements.load(jdbc, userId).statements());
            detection = d.detection();
            mailsStored = all.size();
            statementCount = d.statements();
        } catch (Exception e) {
            if (stillScanned(userId, connectionId)) failed(s, "DETECT_" + e.getClass().getSimpleName());
            throw e;
        }
        s.setStatus(AccountDetectState.Status.IDLE);
        if (!stillScanned(userId, connectionId)) return mailboxGone();
        store.save(s);

        sink.accept(userId, detection, new DetectionContext(connection.getId(), firstScan, firstScanCompleted, mailsStored, scan.mails().size(), statementCount, now));
        log.info("Account detect finished: firstScan={}, firstScanDone={}, listed={}, alreadyStored={}, newMails={}, partialMails={}, givenUpMails={}, mailsStored={}, entities={}, durationMs={}, stageMs={}",
                firstScan, s.getFirstScanDoneAt() != null, scan.listed(), scan.skippedKnown(), scan.mails().size(), incomplete.partial().size(),
                incomplete.givenUp(), mailsStored, detection.entities.size(), System.currentTimeMillis() - started, timings);
        return result("DONE", scan.mails().size(), mailsStored, detection.entities.size());
    }

    /** The user's name, birth date and saved statement passwords (empty when they cannot be read: PDFs then stay locked). */
    private UnlockProfile profileOf(UUID userId) {
        try {
            return profiles.load(userId);
        } catch (Exception e) {
            log.warn("Account detect unlock profile unavailable: {}", e.getClass().getSimpleName());
            return UnlockProfile.of(null, null);
        }
    }

    /** PDFs one retry pass fetches at most for unlocking, and (separately) for the holder recheck; the rest is left for the next run. */
    static final int RETRY_PDF_CAP = 200;
    int retryCap = RETRY_PDF_CAP;

    private static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    private static java.time.YearMonth monthOf(Instant at) {
        return at == null ? null : java.time.YearMonth.from(at.atZone(IST));
    }

    /**
     * Unlock retry: the user's name, birth date or a saved statement password changed. Works through the facts not yet tried at
     * the current generation (see {@link #retryPass}); does nothing when nothing was scanned yet or Gmail is not usable.
     */
    private Map<String, Object> retryUnlock(UUID userId, GmailConnection connection) throws Exception {
        AccountDetectState state = store.state(userId).orElse(null);
        if (profiles == null || state == null || !connection.getId().equals(state.getConnectionId())) {
            return result("SKIPPED_NO_SCAN", 0, 0, 0);
        }
        if (connection.getAuthFailedAt() != null) return result("SKIPPED_PAUSED_AUTH", 0, 0, 0);   // nothing is stamped: all stays pending
        return retryPass(userId, connection, state);
    }

    private static final String HOLDER_UNKNOWN = null;

    /**
     * One pass: reads again the statement PDFs still LOCKED or FAILED that were not tried at the current unlock generation
     * (newest first, at most {@link #retryCap}), merging the content of those that open into their mail's facts; and, after a
     * name change, recomputes the holder flag of opened statements not yet rechecked at the holder generation (a separate cap).
     * Every fact looked at is stamped with the generation read at the start, opened or not, so a capped pass resumes where it
     * stopped and a trigger arriving meanwhile stays pending. Reruns the detector over all stored facts when anything changed.
     */
    private Map<String, Object> retryPass(UUID userId, GmailConnection connection, AccountDetectState state) throws Exception {
        UUID connectionId = connection.getId();
        int unlockGen = state.getUnlockGen();
        int holderGen = state.getHolderGen();
        List<AccountDetectStore.LockedFact> locked = unlockGen > 0 ? store.lockedFacts(userId, unlockGen) : List.of();
        UnlockProfile profile = profileOf(userId);
        java.util.function.UnaryOperator<String> holderCheck = HolderCheck.forName(profile.displayName());
        List<AccountDetectStore.OpenedFact> opened = holderGen > 0 ? store.openedFacts(userId, holderGen) : List.of();
        if (locked.isEmpty() && opened.isEmpty()) return result("DONE", 0, 0, 0);
        int unlocked = 0;
        int holdersChanged = 0;
        int stillClosed = 0;
        boolean capped = locked.size() > retryCap;
        try {
            MailSource.Attachments fetcher = holderCheck == null && locked.isEmpty() ? null : sources.open(connection).attachments();
            for (AccountDetectStore.LockedFact lf : locked.subList(0, Math.min(locked.size(), retryCap))) {
                MailDerived d = lf.facts().derived();
                if (d != null) {
                    byte[] bytes = fetcher.get(lf.messageId(), lf.attachmentId());   // an auth error here stops the pass, nothing stamped
                    if (bytes == null) {
                        stillClosed++;
                    } else {
                        try {
                            StatementUnlocker.Outcome o = unlocker.unlock(bytes, new StatementUnlocker.Target(d.bank(), d.clusterKind(),
                                    PasswordRuleParser.rulesOf(lf.unlockHint()), StatementUnlocker.cardNumbers(d), monthOf(lf.internalDate())), profile);
                            switch (o.state()) {
                                case UNLOCKED -> {
                                    StatementContent c = o.read() == null || o.read().content() == null ? null
                                            : o.read().content().withOrigin(d.clusterId(), d.date()).withUnlockRule(o.rule());
                                    store.applyUnlock(userId, lf.messageId(), AccountDetectFact.UnlockState.UNLOCKED, o.rule(), c);
                                    unlocked++;
                                }
                                case FAILED -> {
                                    store.applyUnlock(userId, lf.messageId(), AccountDetectFact.UnlockState.FAILED, null, null);
                                    stillClosed++;
                                }
                                case LOCKED -> stillClosed++;
                            }
                        } catch (RuntimeException e) {
                            stillClosed++;
                            log.warn("Account detect unlock retry: one PDF failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                        }
                    }
                }
                store.markUnlockTried(userId, lf.messageId(), unlockGen);
            }
            if (holderCheck == null) {
                // no usable name words: whatever Y / N was stored is stale, so it goes (no PDF needed, so no cap)
                for (AccountDetectStore.OpenedFact of : opened) {
                    if (store.applyHolder(userId, of.messageId(), HOLDER_UNKNOWN)) holdersChanged++;
                    store.markHolderChecked(userId, of.messageId(), holderGen);
                }
            } else {
                capped |= opened.size() > retryCap;
                for (AccountDetectStore.OpenedFact of : opened.subList(0, Math.min(opened.size(), retryCap))) {
                    MailDerived d = of.facts().derived();
                    // (only the mail's first PDF is rechecked and its answer applies to all of the mail's statements: accepted)
                    String holder = HOLDER_UNKNOWN;
                    if (d != null) {
                        byte[] bytes = fetcher.get(of.messageId(), of.attachmentId());
                        if (bytes != null) {
                            try {
                                if (of.unlockState() == AccountDetectFact.UnlockState.UNLOCKED) {
                                    StatementUnlocker.Outcome o = unlocker.unlock(bytes, new StatementUnlocker.Target(d.bank(), d.clusterKind(),
                                            PasswordRuleParser.rulesOf(of.unlockHint()), StatementUnlocker.cardNumbers(d), monthOf(of.internalDate())), profile);
                                    if (o.state() == StatementUnlocker.State.UNLOCKED && o.read() != null && o.read().content() != null) holder = o.read().content().holder();
                                } else {
                                    StatementPdfReader.Result r = StatementPdfReader.read(bytes, "PDF_MAIL", d.bank(), monthOf(of.internalDate()), null, holderCheck);
                                    if (r.status() == StatementPdfReader.Status.OPENED && r.content() != null) holder = r.content().holder();
                                }
                            } catch (RuntimeException e) {
                                log.warn("Account detect holder recheck: one PDF failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                            }
                        }
                    }
                    // a PDF that opened before but cannot be opened (or fetched) now leaves the holder unknown, not stale
                    if (store.applyHolder(userId, of.messageId(), holder)) holdersChanged++;
                    store.markHolderChecked(userId, of.messageId(), holderGen);
                }
            }
        } catch (GmailEngineException e) {
            if (e.getErrorType() == GmailError.AUTH_ERROR) {
                if (!stillScanned(userId, connectionId)) return mailboxGone();
                pausedForAuth(state, connectionId);
                return result(AccountDetectState.Status.PAUSED_AUTH.name(), 0, locked.size(), 0);
            }
            throw e;
        }
        Map<String, Object> out = rerun(userId, connectionId, unlocked + holdersChanged, locked.size(), stillClosed);
        if (capped) out.put("capped", true);
        return out;
    }

    /** Reruns detection over all stored facts and calls the sink, when the pass changed anything. */
    private Map<String, Object> rerun(UUID userId, UUID connectionId, int changed, int tried, int stillClosed) {
        if (changed == 0) {
            log.info("Account detect unlock retry: tried={}, changed=0, stillClosed={}", tried, stillClosed);
            return result("DONE", 0, tried, 0);
        }
        List<AccountDetectStore.StoredFact> all = store.facts(userId);
        Detected d = detect(all, DbStatements.load(jdbc, userId).statements());
        if (!stillScanned(userId, connectionId)) return new LinkedHashMap<>(mailboxGone());
        Instant now = AppTime.nowInstant();
        sink.accept(userId, d.detection(), new DetectionContext(connectionId, false, false, all.size(), 0, d.statements(), now));
        log.info("Account detect unlock retry: tried={}, changed={}, stillClosed={}, mailsStored={}, entities={}", tried, changed, stillClosed,
                all.size(), d.detection().entities.size());
        return result("DONE", 0, all.size(), d.detection().entities.size());
    }

    record Detected(Detection detection, int statements) {}

    /**
     * Mails of this run a read failed for. {@code partial}: still to be read again, with their internal dates (null when
     * the headers were not read); {@code givenUp}: failed {@link FactsCodec#GIVE_UP_AFTER_RUNS} runs in a row, kept as is.
     */
    record Incomplete(List<Instant> partial, int givenUp) {}

    static Incomplete incomplete(AccountDetectScan.Result scan, List<ScannedMail> headersMissing, Map<String, Integer> partialRuns) {
        List<Instant> partial = new ArrayList<>();
        int givenUp = 0;
        List<ScannedMail> all = new ArrayList<>(scan.mails());
        all.addAll(headersMissing);
        for (ScannedMail m : all) {
            if (!m.incomplete()) continue;
            if (AccountDetectStore.incompleteRuns(m, partialRuns) >= FactsCodec.GIVE_UP_AFTER_RUNS) givenUp++;
            else partial.add(m.internalDate());
        }
        return new Incomplete(partial, givenUp);
    }

    /**
     * The marker never passes a mail still to be read again: it stays below the oldest one's date (and does not move
     * when one has no date), so the next run's listing (marker minus the overlap) still holds it. It never goes back.
     */
    static Instant nextMarker(Instant marker, Instant newest, Incomplete incomplete) {
        Instant next = newest;
        if (!incomplete.partial().isEmpty()) {
            if (incomplete.partial().stream().anyMatch(java.util.Objects::isNull)) return marker;
            Instant cap = incomplete.partial().stream().min(Instant::compareTo).orElseThrow().minusMillis(1);
            if (next == null || cap.isBefore(next)) next = cap;
        }
        if (next == null) return marker;
        return marker == null || next.isAfter(marker) ? next : marker;
    }

    /** The mailbox stopped being the scanned one mid-run (disconnected, or its stored data deleted). */
    static final class MailboxGone extends RuntimeException {
        MailboxGone() {
            super(null, null, false, false);
        }
    }

    /** True while the connection is still connected and the user's state row still exists (fresh reads). */
    private boolean stillScanned(UUID userId, UUID connectionId) {
        GmailConnection c = connectionRepository.findById(connectionId).orElse(null);
        return c != null && Boolean.TRUE.equals(c.getIsConnected()) && store.state(userId).isPresent();
    }

    private void requireStillScanned(UUID userId, UUID connectionId) {
        if (!stillScanned(userId, connectionId)) throw new MailboxGone();
    }

    private Map<String, Object> mailboxGone() {
        log.info("Account detect stopped: the mailbox was disconnected during the run, nothing more saved");
        return result("ABORTED_DISCONNECTED", 0, 0, 0);
    }

    /**
     * The detector over stored facts, in their stored order (internal date, then message id): mail statement content
     * in that order, then app-parsed statements. Same as the v3 runner's detection (bootstrap stability included).
     */
    static Detected detect(List<AccountDetectStore.StoredFact> facts, List<StatementContent> dbStatements) {
        List<MailDerived> derived = new ArrayList<>(facts.size());
        List<StatementContent> statements = new ArrayList<>();
        for (AccountDetectStore.StoredFact f : facts) {
            if (f.facts().derived() == null) continue;   // headers never read: nothing to detect from
            derived.add(f.facts().derived());
            statements.addAll(f.facts().statements());
        }
        statements.addAll(dbStatements);
        return new Detected(Stability.detectStable(derived, statements, STABILITY_SEED).detection(), statements.size());
    }

    /** Sender tokens exactly like the v3 runner: every bank domain, then the user's own allow-listed sender addresses. */
    private List<String> tokens(UUID userId) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String d : BankDirectory.allDomains()) tokens.add(d.toLowerCase(Locale.ROOT));
        senderRepository.findByUserId(userId).stream()
                .map(GmailSender::getSenderAddress).filter(x -> x != null && !x.isBlank())
                .map(x -> x.trim().toLowerCase(Locale.ROOT)).forEach(tokens::add);
        return new ArrayList<>(tokens);
    }

    private void pausedForAuth(AccountDetectState state, UUID connectionId) {
        state.setConnectionId(connectionId);
        state.setStatus(AccountDetectState.Status.PAUSED_AUTH);
        state.setLastError(GmailError.AUTH_ERROR.name());
        store.save(state);
        log.info("Account detect paused: Gmail access expired, waiting for reconnect");
    }

    private void failed(AccountDetectState state, String code) {
        state.setStatus(AccountDetectState.Status.FAILED);
        state.setLastError(code.length() > 40 ? code.substring(0, 40) : code);
        try {
            store.save(state);
        } catch (Exception ignored) {
            // the job fails with the original error
        }
        log.warn("Account detect failed: code={}", state.getLastError());
    }

    private static Map<String, Object> result(String status, int newMails, int mailsStored, int entities) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", status);
        r.put("newMails", newMails);
        r.put("mailsStored", mailsStored);
        r.put("entities", entities);
        return r;
    }
}
