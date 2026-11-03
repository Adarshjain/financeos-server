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
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.scan.AccountDetectScan;
import com.financeos.gmail.accountdetect.scan.DbStatements;
import com.financeos.gmail.accountdetect.scan.MailSource;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSender;
import com.financeos.gmail.ingest.GmailSenderRepository;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 *   <li>Gmail access expired: state PAUSED_AUTH, everything stored is kept, the job ends cleanly (no retry).</li>
 *   <li>Protected statement PDFs are recorded as LOCKED; no password is ever tried.</li>
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

    public AccountDetectJobHandler(GmailConnectionRepository connectionRepository, GmailSenderRepository senderRepository,
                                   AccountDetectStore store, MailSourceFactory sources, FirstScanGuard guard,
                                   AccountDetectTriggers triggers, DetectionSink sink, JdbcTemplate jdbc,
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
        for (AccountDetectStore.StoredFact f : stored) {
            known.add(f.messageId());
            prior.add(new AccountDetectScan.Prior(f.facts().derived(), f.facts().bodyFetched()));
        }
        Instant after = firstScan || state[0].getMarkerAt() == null ? null : state[0].getMarkerAt().minus(MARKER_OVERLAP);

        Map<String, Long> timings = new LinkedHashMap<>();
        AccountDetectScan.Result scan;
        try {
            MailSource source = sources.open(connection);
            scan = new AccountDetectScan(alertSample, chunkSize).run(source, tokens(userId), after, known, prior, salt,
                    chunk -> store.saveAll(userId, chunk),
                    (seen, total) -> {
                        state[0].setMailsSeen(seen);
                        state[0].setMailsTotal(total);
                        state[0] = store.save(state[0]);
                        ctx.progress(seen, total, "Reading bank mail");
                        ctx.checkCancelled();
                    },
                    line -> log.debug("Account detect {}", line), timings);
        } catch (GmailEngineException e) {
            if (e.getErrorType() == GmailError.AUTH_ERROR) {
                pausedForAuth(state[0], connection.getId());
                return result(AccountDetectState.Status.PAUSED_AUTH.name(), 0, stored.size(), 0);
            }
            failed(state[0], "GMAIL_" + e.getErrorType().name());
            throw e;
        } catch (JobCancelledException e) {
            state[0].setStatus(AccountDetectState.Status.IDLE);
            store.save(state[0]);
            throw e;
        } catch (Exception e) {
            failed(state[0], e.getClass().getSimpleName());
            throw e;
        }

        Instant newest = scan.newest();
        AccountDetectState s = state[0];
        if (newest != null && (s.getMarkerAt() == null || newest.isAfter(s.getMarkerAt()))) s.setMarkerAt(newest);
        Instant now = AppTime.nowInstant();
        if (firstScan) s.setFirstScanDoneAt(now);
        s.setLastRunAt(now);
        s.setMailsSeen(scan.mails().size());
        s.setMailsTotal(scan.mails().size());

        Detection detection;
        int mailsStored;
        int statementCount;
        try {
            List<AccountDetectStore.StoredFact> all = store.facts(userId);
            Detected d = detect(all, DbStatements.load(jdbc, userId).statements());
            detection = d.detection();
            mailsStored = all.size();
            statementCount = d.statements();
        } catch (Exception e) {
            failed(s, "DETECT_" + e.getClass().getSimpleName());
            throw e;
        }
        s.setStatus(AccountDetectState.Status.IDLE);
        store.save(s);

        sink.accept(userId, detection, new DetectionContext(connection.getId(), firstScan, mailsStored, scan.mails().size(), statementCount, now));
        log.info("Account detect finished: firstScan={}, listed={}, alreadyStored={}, newMails={}, mailsStored={}, entities={}, durationMs={}, stageMs={}",
                firstScan, scan.listed(), scan.skippedKnown(), scan.mails().size(), mailsStored, detection.entities.size(),
                System.currentTimeMillis() - started, timings);
        return result("DONE", scan.mails().size(), mailsStored, detection.entities.size());
    }

    record Detected(Detection detection, int statements) {}

    /**
     * The detector over stored facts, in their stored order (internal date, then message id): mail statement content
     * in that order, then app-parsed statements. Same as the v3 runner's detection (bootstrap stability included).
     */
    static Detected detect(List<AccountDetectStore.StoredFact> facts, List<StatementContent> dbStatements) {
        List<MailDerived> derived = new ArrayList<>(facts.size());
        List<StatementContent> statements = new ArrayList<>();
        for (AccountDetectStore.StoredFact f : facts) {
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
