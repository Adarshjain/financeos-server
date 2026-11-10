package com.financeos.domain.statement.file;

import com.financeos.domain.statement.StatementRepository;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.reconcile.StatementOriginalFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Once per statement, fetches the original file of an old Gmail statement (ingested before files were kept) from its
 * mailbox and attaches it. Scheduled, so it never runs on the api role. Gmail calls and the attach run with no
 * transaction open; each outcome is written in its own short transaction.
 *
 * <p>Every outcome stamps {@code file_backfill_at}. Final outcomes are never selected again; the retryable ones
 * ({@code NO_CONNECTION}, {@code ERROR}, {@code STORE_UNAVAILABLE}) are selected again only after the retry window.
 * A connection-level condition (no usable mailbox, or Gmail refusing: auth, network, rate limit) ends that user's work
 * for the run and stamps the user's remaining statements of the batch alike, so a dead mailbox costs one lookup per
 * window. A failure specific to one statement never stops its siblings. The store being unavailable is not per user:
 * it ends the whole run (the rest stay unstamped). A run also ends when its time budget is spent (the default
 * scheduler has one thread shared with the pollers). Two nodes at once can at worst fetch a file twice: the store
 * dedupes by (user, sha), the attach sets {@code file_id} only while it is null, and the stamp never overwrites a
 * final outcome.
 */
@Component
public class StatementFileBackfill {

    private static final Logger log = LoggerFactory.getLogger(StatementFileBackfill.class);

    public enum Outcome {
        ATTACHED(true), MAIL_GONE(true), NO_MATCHING_ATTACHMENT(true), NOT_STORED(true),
        NO_CONNECTION(false), ERROR(false), STORE_UNAVAILABLE(false);

        final boolean finalOutcome;

        Outcome(boolean finalOutcome) {
            this.finalOutcome = finalOutcome;
        }
    }

    /** What handling one statement ended in, and how far it reaches: this user only, or the whole run. */
    private record Handled(Outcome outcome, boolean stopsUser, boolean stopsRun) {
        static Handled of(Outcome outcome) {
            return new Handled(outcome, false, false);
        }
    }

    private final StatementRepository statementRepository;
    private final GmailConnectionRepository connectionRepository;
    private final StatementOriginalFetcher fetcher;
    private final StatementFileAttacher attacher;
    private final StatementFileMetrics metrics;
    private final StatementFileProperties fileProperties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    private final int batch;
    private final Duration retryWindow;
    private final Duration budget;

    @Autowired
    public StatementFileBackfill(StatementRepository statementRepository,
                                 GmailConnectionRepository connectionRepository,
                                 StatementOriginalFetcher fetcher,
                                 StatementFileAttacher attacher,
                                 StatementFileMetrics metrics,
                                 StatementFileProperties fileProperties,
                                 PlatformTransactionManager txManager,
                                 ObjectProvider<Clock> clock,
                                 @Value("${app.statement-files.backfill-enabled:true}") boolean enabled,
                                 @Value("${app.statement-files.backfill-batch:10}") int batch,
                                 @Value("${app.statement-files.backfill-retry-hours:24}") long retryHours,
                                 @Value("${app.statement-files.backfill-budget-seconds:45}") long budgetSeconds) {
        this(statementRepository, connectionRepository, fetcher, attacher, metrics, fileProperties, txManager,
                clock.getIfAvailable(Clock::systemUTC), enabled, batch, retryHours, budgetSeconds);
    }

    StatementFileBackfill(StatementRepository statementRepository,
                          GmailConnectionRepository connectionRepository,
                          StatementOriginalFetcher fetcher,
                          StatementFileAttacher attacher,
                          StatementFileMetrics metrics,
                          StatementFileProperties fileProperties,
                          PlatformTransactionManager txManager,
                          Clock clock,
                          boolean enabled,
                          int batch,
                          long retryHours,
                          long budgetSeconds) {
        this.statementRepository = statementRepository;
        this.connectionRepository = connectionRepository;
        this.fetcher = fetcher;
        this.attacher = attacher;
        this.metrics = metrics;
        this.fileProperties = fileProperties;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.enabled = enabled;
        this.batch = batch;
        this.retryWindow = Duration.ofHours(retryHours);
        this.budget = Duration.ofSeconds(budgetSeconds);
    }

    @Scheduled(cron = "${app.statement-files.backfill-cron:0 17,47 * * * *}", zone = "${jobs.retention.zone:Asia/Kolkata}")
    public void run() {
        if (!enabled || batch <= 0 || !fileProperties.isEnabled()) {
            return;
        }
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        try {
            Instant deadline = clock.instant().plus(budget);
            List<StatementRepository.FileBackfillCandidate> candidates =
                    statementRepository.findFileBackfillCandidates(batch, clock.instant().minus(retryWindow));
            if (candidates.isEmpty()) {
                return;
            }
            Map<String, Outcome> stoppedUsers = new HashMap<>();
            Map<String, List<GmailConnection>> usable = new HashMap<>();
            for (StatementRepository.FileBackfillCandidate c : candidates) {
                Outcome stopped = stoppedUsers.get(c.getUserId());
                if (stopped != null) {
                    // A sibling of a user-level retryable outcome: same outcome and time, no Gmail call.
                    stamp(c, stopped);
                    continue;
                }
                if (!clock.instant().isBefore(deadline)) {
                    break;
                }
                Handled h = handle(c, usable);
                counts.merge(h.outcome(), 1, Integer::sum);
                metrics.backfill(h.outcome().name());
                if (h.stopsRun()) {
                    break;
                }
                if (h.stopsUser()) {
                    stoppedUsers.put(c.getUserId(), h.outcome());
                }
            }
        } catch (Exception e) {
            log.warn("Statement file back-fill run failed: {}", e.toString());
        }
        if (!counts.isEmpty()) {
            log.info("Statement file back-fill {}", counts);
        }
    }

    private Handled handle(StatementRepository.FileBackfillCandidate c, Map<String, List<GmailConnection>> usable) {
        UUID statementId;
        try {
            statementId = UUID.fromString(c.getId());
            UUID userId = UUID.fromString(c.getUserId());
            List<GmailConnection> connections = usable.computeIfAbsent(c.getUserId(), k -> usableConnections(userId));
            if (connections.isEmpty()) {
                stamp(c, Outcome.NO_CONNECTION);
                return new Handled(Outcome.NO_CONNECTION, true, false);
            }
            StatementOriginalFetcher.Result found;
            try {
                found = fetchFromAny(order(connections, userId, c.getSourceRef()), c);
            } catch (GmailEngineException e) {
                // Auth, network, rate limit...: the mailbox, not this statement, is the problem.
                log.warn("Statement file back-fill Gmail failure statement={} user={}: {}", c.getId(), c.getUserId(), e.getErrorType());
                stamp(c, Outcome.ERROR);
                return new Handled(Outcome.ERROR, true, false);
            }
            Outcome outcome;
            switch (found.kind()) {
                case MAIL_GONE -> outcome = Outcome.MAIL_GONE;
                case NO_MATCHING_ATTACHMENT -> outcome = Outcome.NO_MATCHING_ATTACHMENT;
                default -> {
                    StatementFileAttacher.AttachResult r = attacher.attachDetailed(userId, statementId, found.bytes(), found.name());
                    switch (r) {
                        case ATTACHED, ALREADY_HAD_FILE -> outcome = Outcome.ATTACHED;
                        case NOT_STORED_TRANSIENT -> {
                            stamp(c, Outcome.STORE_UNAVAILABLE);
                            return new Handled(Outcome.STORE_UNAVAILABLE, false, true);
                        }
                        default -> outcome = Outcome.NOT_STORED; // permanent (too large, unsupported, empty) or statement gone
                    }
                }
            }
            settle(statementId, outcome);
            return Handled.of(outcome);
        } catch (Exception e) {
            log.warn("Statement file back-fill failed statement={} user={}: {}", c.getId(), c.getUserId(), e.getClass().getSimpleName());
            stamp(c, Outcome.ERROR);
            return Handled.of(Outcome.ERROR);
        }
    }

    /**
     * Each connection in turn (the hash match makes a wrong mailbox harmless): the first that holds the file wins;
     * otherwise "no attachment with that hash" beats "gone" when any mailbox had the message.
     */
    private StatementOriginalFetcher.Result fetchFromAny(List<GmailConnection> connections, StatementRepository.FileBackfillCandidate c) {
        boolean messageSeen = false;
        for (GmailConnection connection : connections) {
            StatementOriginalFetcher.Result r = fetcher.fetch(connection, c.getSourceRef(), c.getFileSha256());
            if (r.kind() == StatementOriginalFetcher.Kind.FOUND) {
                return r;
            }
            messageSeen |= r.kind() == StatementOriginalFetcher.Kind.NO_MATCHING_ATTACHMENT;
        }
        return new StatementOriginalFetcher.Result(messageSeen
                ? StatementOriginalFetcher.Kind.NO_MATCHING_ATTACHMENT : StatementOriginalFetcher.Kind.MAIL_GONE, null, null);
    }

    /** The mailbox whose ledger holds the message first (when it is usable), then the user's other usable ones. */
    private List<GmailConnection> order(List<GmailConnection> usable, UUID userId, String messageId) {
        List<UUID> owners = new ArrayList<>();
        for (GmailConnection owner : connectionRepository.findOwningMessage(userId, messageId)) {
            owners.add(owner.getId());
        }
        List<GmailConnection> ordered = new ArrayList<>(usable);
        ordered.sort((a, b) -> Boolean.compare(owners.contains(b.getId()), owners.contains(a.getId())));
        return ordered;
    }

    /** The user's own connected mailboxes whose token Google still accepts. */
    private List<GmailConnection> usableConnections(UUID userId) {
        List<GmailConnection> usable = new ArrayList<>();
        for (GmailConnection connection : connectionRepository.findAllByUserId(userId)) {
            if (connection.getUser() != null && userId.equals(connection.getUser().getId())
                    && Boolean.TRUE.equals(connection.getIsConnected()) && connection.getAuthFailedAt() == null) {
                usable.add(connection);
            }
        }
        return usable;
    }

    /** Stamps without ever throwing (a failed stamp only means the statement is looked at again next run). */
    private void stamp(StatementRepository.FileBackfillCandidate c, Outcome outcome) {
        try {
            settle(UUID.fromString(c.getId()), outcome);
        } catch (Exception e) {
            log.warn("Statement file back-fill could not stamp statement={}: {}", c.getId(), e.getClass().getSimpleName());
        }
    }

    private void settle(UUID statementId, Outcome outcome) {
        tx.executeWithoutResult(s -> statementRepository.markFileBackfill(statementId, Instant.now(clock), outcome.name()));
    }
}
