package com.financeos.domain.statement.reparse;

import com.financeos.core.security.UserContext;
import com.financeos.core.security.UserFilterEnabler;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.ingestion.ReconciledRefresh;
import com.financeos.domain.ingestion.StatementOpener;
import com.financeos.domain.ingestion.StatementPasswordFinder;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementImportGate;
import com.financeos.domain.statement.StatementParseRecords;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.file.StatementFileStore;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.statement.parser.FileType;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.StatementParseException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * When the parser improves ({@link StatementParseEngine#PARSER_VERSION} rises), re-reads each statement whose original
 * file is stored and compares the result with what is stored. Nothing is changed unless the result is identical (the
 * statement is then stamped with the new parser version) or {@code app.statement-reparse.apply} is on. Differences are
 * only recorded ({@code reparse_*} columns: outcome, and field names / counts, never values). Each statement is
 * examined once per parser version. Scheduled, so it never runs on the api role; the file load and the parse run with
 * no transaction open. The "what would a refresh do" step runs the real refresh in a transaction that is always rolled
 * back.
 */
@Component
public class StatementReparse {

    private static final Logger log = LoggerFactory.getLogger(StatementReparse.class);
    static final int SUMMARY_MAX = 500;
    /** An ERROR, a locked PARSE_FAILED (summary LOCKED*) or an IN_PROGRESS never settled is retried after this long, up to MAX_ATTEMPTS per parser version. */
    static final Duration RETRY_AFTER = Duration.ofHours(24);
    static final int MAX_ATTEMPTS = 3;

    public enum Outcome {
        /** The file is gone or unreadable from the store. */
        FILE_MISSING,
        /** The parse failed; the summary is the failure kind. */
        PARSE_FAILED,
        /** The import gate blocked the parse; the summary is the reason. */
        BLOCKED,
        /** The parse found no lines. */
        EMPTY,
        /** The parse does not reconcile, so nothing can be applied safely. */
        NOT_RECONCILED,
        /** The stored statement could not be matched to the parse (hash / account / period). */
        NO_TARGET,
        /** The stored data equals the parse; the statement was stamped with the new parser version. */
        SAME,
        /** Differences found (changed fields, rows to add, extras or re-linked lines), apply is off: nothing changed. */
        WOULD_CHANGE,
        /** The only differences are fields the parse would clear: never applied automatically (a human decides). */
        WOULD_CLEAR,
        /** Apply is on and the statement would still come out WOULD_CHANGE: parked so it is not selected again. */
        APPLY_SKIPPED,
        /** The statement changed under the job (a concurrent refresh, re-upload or delete): nothing stamped on its data. */
        SKIPPED_CHANGED,
        /** Written before a statement is examined; a statement that kills the JVM stays parked here until the retry window. */
        IN_PROGRESS,
        /** Differences found and applied like a reconciling re-upload. */
        REFRESHED,
        ERROR
    }

    /** One statement's result: the outcome and its summary. */
    record Result(Outcome outcome, String summary) {
        static Result of(Outcome outcome) {
            return new Result(outcome, null);
        }
    }

    private final StatementRepository statementRepository;
    private final StatementFileStore store;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final StatementOpener opener;
    private final StatementPasswordFinder passwordFinder;
    private final StatementPersistenceService persistence;
    private final ReconciledRefresh refresh;
    private final StatementParseRecords parseRecords;
    private final UserFilterEnabler userFilterEnabler;
    private final MeterRegistry registry;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int currentVersion;
    private final boolean enabled;
    private final boolean apply;
    private final int batch;
    private final Duration budget;
    /** Locked files opened by the wider password search in the current run, by source (the scheduler runs one at a time). */
    private final Map<StatementPasswordFinder.Source, Integer> unlocked = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public StatementReparse(StatementRepository statementRepository, StatementFileStore store,
                            AccountRepository accountRepository, UserRepository userRepository,
                            StatementOpener opener, StatementPasswordFinder passwordFinder,
                            StatementPersistenceService persistence, ReconciledRefresh refresh,
                            StatementParseRecords parseRecords, UserFilterEnabler userFilterEnabler,
                            MeterRegistry registry,
                            PlatformTransactionManager txManager, ObjectProvider<Clock> clock,
                            @Value("${app.statement-reparse.enabled:true}") boolean enabled,
                            @Value("${app.statement-reparse.apply:false}") boolean apply,
                            @Value("${app.statement-reparse.batch:5}") int batch,
                            @Value("${app.statement-reparse.budget-seconds:45}") long budgetSeconds) {
        this(statementRepository, store, accountRepository, userRepository, opener, passwordFinder, persistence, refresh,
                parseRecords,
                userFilterEnabler, registry, txManager,
                clock.getIfAvailable(Clock::systemUTC), StatementParseEngine.PARSER_VERSION, enabled, apply, batch,
                budgetSeconds);
    }

    StatementReparse(StatementRepository statementRepository, StatementFileStore store,
                     AccountRepository accountRepository, UserRepository userRepository,
                     StatementOpener opener, StatementPasswordFinder passwordFinder,
                     StatementPersistenceService persistence, ReconciledRefresh refresh,
                     StatementParseRecords parseRecords, UserFilterEnabler userFilterEnabler,
                     MeterRegistry registry,
                     PlatformTransactionManager txManager, Clock clock, int currentVersion,
                     boolean enabled, boolean apply, int batch, long budgetSeconds) {
        this.statementRepository = statementRepository;
        this.store = store;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.opener = opener;
        this.passwordFinder = passwordFinder;
        this.persistence = persistence;
        this.refresh = refresh;
        this.parseRecords = parseRecords;
        this.userFilterEnabler = userFilterEnabler;
        this.registry = registry;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.currentVersion = currentVersion;
        this.enabled = enabled;
        this.apply = apply;
        this.batch = batch;
        this.budget = Duration.ofSeconds(budgetSeconds);
    }

    @Scheduled(cron = "${app.statement-reparse.cron:0 7,37 * * * *}", zone = "${jobs.retention.zone:Asia/Kolkata}")
    public void run() {
        if (!enabled || batch <= 0) {
            return;
        }
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        unlocked.clear();
        try {
            Instant deadline = clock.instant().plus(budget);
            List<StatementRepository.ReparseCandidate> candidates =
                    statementRepository.findReparseCandidates(batch, currentVersion,
                            clock.instant().minus(RETRY_AFTER), MAX_ATTEMPTS, apply ? 1 : 0);
            for (StatementRepository.ReparseCandidate c : candidates) {
                if (!clock.instant().isBefore(deadline)) {
                    break;
                }
                Result r = handle(c);
                counts.merge(r.outcome(), 1, Integer::sum);
                Counter.builder("financeos.statement.reparse").tag("outcome", r.outcome().name())
                        .tag("applied", String.valueOf(r.outcome() == Outcome.REFRESHED)).register(registry).increment();
            }
        } catch (Exception e) {
            log.warn("Statement re-read run failed: {}", e.toString());
        }
        if (!counts.isEmpty()) {
            log.info("Statement re-read (parser version {}, apply={}) {} unlocked={}", currentVersion, apply, counts,
                    new java.util.TreeMap<>(unlocked));
        }
    }

    /** Examines one statement as its own user, then stamps the outcome; never throws. */
    private Result handle(StatementRepository.ReparseCandidate c) {
        UUID previousUser = UserContext.getCurrentUserId();
        Result result;
        UUID statementId;
        try {
            statementId = UUID.fromString(c.getId());
        } catch (Exception e) {
            log.warn("Statement re-read: unusable candidate id");
            return Result.of(Outcome.ERROR);
        }
        try {
            UUID userId = UUID.fromString(c.getUserId());
            // The job runs outside a request: the tenant filter and anything reading the current user need this
            // (same as the Gmail drain continuation); restored in finally.
            UserContext.setCurrentUserId(userId);
            // Park the statement first: one that kills or exhausts the JVM is then not picked first again next run.
            if (tx.execute(status -> statementRepository.markReparseStarted(statementId, currentVersion, clock.instant())) != 1) {
                result = Result.of(Outcome.SKIPPED_CHANGED);
            } else {
                result = examine(c, statementId, userId);
            }
        } catch (Exception | StackOverflowError e) {
            // OutOfMemoryError is deliberately not caught: it propagates, the IN_PROGRESS stamp keeps the statement parked.
            log.warn("Statement re-read failed statement={}: {}", c.getId(), e.getClass().getSimpleName());
            result = Result.of(Outcome.ERROR);
        } finally {
            if (previousUser != null) {
                UserContext.setCurrentUserId(previousUser);
            } else {
                UserContext.clear();
            }
        }
        if (apply && result.outcome() == Outcome.WOULD_CHANGE) {
            // An apply attempt must end in another outcome, or the statement would be selected again forever.
            result = new Result(Outcome.APPLY_SKIPPED, result.summary());
        }
        if (result.outcome() != Outcome.SAME) {
            // SAME was stamped together with the parser version in its own transaction. Settles IN_PROGRESS.
            stamp(statementId, result);
        }
        return result;
    }

    private static boolean isLocked(StatementExtractionResult parsed) {
        return parsed.failureKind() == StatementParseException.Kind.LOCKED
                || (parsed.failureKind() == StatementParseException.Kind.OTHER
                && StatementParseException.isPasswordMessage(parsed.failureReason()));
    }

    /** As the parser decides: the %PDF- header within the first 1024 bytes (some banks put a preamble before it). */
    private static boolean isPdf(byte[] bytes) {
        return FileType.pdfHeaderOffset(bytes) >= 0;
    }

    private void unlockCounter(String source) {
        Counter.builder("financeos.statement.reparse.unlock").tag("source", source).register(registry).increment();
    }

    private Result examine(StatementRepository.ReparseCandidate c, UUID statementId, UUID userId) {
        UUID accountId = UUID.fromString(c.getAccountId());
        UUID fileId = UUID.fromString(c.getFileId());
        Optional<byte[]> loaded = store.load(userId, fileId);
        if (loaded.isEmpty()) {
            return Result.of(Outcome.FILE_MISSING);
        }
        byte[] bytes = loaded.get();
        Optional<String> accountPassword = savedPassword(userId, accountId);
        if (accountPassword == null) {
            return new Result(Outcome.NO_TARGET, "account");
        }
        StatementExtractionResult parsed = opener.open(userId, accountId, bytes, null, accountPassword.orElse(null), false).result();
        if (!parsed.success() && isLocked(parsed)) {
            // The upload path's passwords did not open it: the Gmail path's wider search (other accounts' passwords with
            // case variants, passwords built from the bank's rules). No transaction is open here; nothing is saved.
            StatementPasswordFinder.Found found = passwordFinder.find(userId, accountId, bytes, isPdf(bytes),
                    accountPassword.map(Set::of).orElse(Set.of()));
            unlockCounter(found.opened() ? found.source().name() : found.capHit() ? "CAP" : "NONE");
            if (!found.opened()) {
                return new Result(Outcome.PARSE_FAILED, found.capHit() ? "LOCKED:cap" : "LOCKED");
            }
            unlocked.merge(found.source(), 1, Integer::sum);
            parsed = opener.open(userId, accountId, bytes, found.password(), accountPassword.orElse(null), false).result();
        }
        if (!parsed.success()) {
            return new Result(Outcome.PARSE_FAILED, parsed.failureKind() == null ? "UNKNOWN" : parsed.failureKind().name());
        }
        Optional<StatementImportGate.Blocked> blocked = StatementImportGate.check(parsed);
        if (blocked.isPresent()) {
            return new Result(Outcome.BLOCKED, blocked.get().reason().name());
        }
        List<ParsedStatementLine> lines = parsed.lines();
        if (lines == null || lines.isEmpty()) {
            return Result.of(Outcome.EMPTY);
        }
        StatementDraft draft = parsed.draft();
        if (!StatementPersistenceService.fullyReconciles(draft)) {
            return new Result(Outcome.NOT_RECONCILED, draft == null ? "no draft"
                    : "verdict=" + draft.verdict() + " mode=" + draft.parseMode());
        }
        String sha = StatementPersistenceService.sha256Hex(bytes);
        StatementDraft finalDraft = draft;

        // What a refresh would do, without doing it: the real refresh in a transaction that is always rolled back.
        record Dry(StatementPersistenceService.Refreshed refreshed, StatementFields.Stored stored) {}
        Dry dry = tx.execute(status -> {
            try {
                userFilterEnabler.enable();
                StatementFields.Stored before = StatementFields.snapshot(
                        statementRepository.findById(statementId).orElse(null));
                Optional<StatementPersistenceService.Refreshed> r =
                        persistence.refreshReconciled(accountId, sha, finalDraft, lines, Set.of());
                return r == null ? null : r.filter(x -> statementId.equals(x.statement().getId()))
                        .map(x -> new Dry(x, before)).orElse(null);
            } finally {
                status.setRollbackOnly();
            }
        });
        if (dry == null) {
            return Result.of(Outcome.NO_TARGET);
        }
        StatementPersistenceService.Refreshed would = dry.refreshed();
        StatementFields.Split split = StatementFields.split(dry.stored(), finalDraft, would.changedFields());
        if (split.changed().isEmpty() && split.cleared().isEmpty() && would.missingLines().isEmpty()
                && would.extraTransactionIds().isEmpty() && would.relinked() == 0) {
            Boolean stamped = tx.execute(status -> {
                Instant now = clock.instant();
                if (statementRepository.markParsedAtVersion(statementId, currentVersion, now) != 1) {
                    return false; // changed under us (concurrent refresh / re-upload / delete)
                }
                statementRepository.markReparse(statementId, currentVersion, now, Outcome.SAME.name(), null);
                return true;
            });
            if (!Boolean.TRUE.equals(stamped)) {
                return Result.of(Outcome.SKIPPED_CHANGED);
            }
            // After commit, like the upload: the record now describes the stored parse.
            parseRecords.saveQuietly(statementId, finalDraft.parserVersion(), finalDraft.parseSnapshot());
            return Result.of(Outcome.SAME);
        }
        String summary = summarise(split.changed(), split.cleared(), would.missingLines().size(),
                would.extraTransactionIds().size(), would.relinked());
        boolean onlyCleared = split.changed().isEmpty() && would.missingLines().isEmpty()
                && would.extraTransactionIds().isEmpty() && would.relinked() == 0;
        // Clearing stored data (it may be AI-filled or user-entered) is a human decision: never applied by the job.
        if (onlyCleared) {
            return new Result(Outcome.WOULD_CLEAR, summary);
        }
        if (!apply) {
            return new Result(Outcome.WOULD_CHANGE, summary);
        }
        // Exactly the reconciling re-upload (refresh + matcher for the missing lines, one transaction), except that
        // fields the parse would clear keep their stored values: restored on the managed entity before commit.
        record Done(ReconciledRefresh.Applied applied, StatementFields.Split split) {}
        Done done = tx.execute(status -> {
            userFilterEnabler.enable();
            Account account = accountRepository.findByIdAndUser_Id(accountId, userId).orElse(null);
            if (account == null) {
                status.setRollbackOnly();
                return null;
            }
            StatementFields.Stored before = StatementFields.snapshot(statementRepository.findById(statementId).orElse(null));
            Optional<ReconciledRefresh.Applied> r = refresh.apply(userRepository.getReferenceById(userId), account,
                    accountId, sha, finalDraft, lines, Set.of());
            if (r.isEmpty() || !statementId.equals(r.get().refreshed().statement().getId())) {
                status.setRollbackOnly();
                return null;
            }
            StatementFields.Split real = StatementFields.split(before, finalDraft, r.get().refreshed().changedFields());
            StatementFields.restore(r.get().refreshed().statement(), before, real.cleared());
            return new Done(r.get(), real);
        });
        if (done == null) {
            return Result.of(Outcome.NO_TARGET);
        }
        parseRecords.saveQuietly(statementId, finalDraft.parserVersion(), finalDraft.parseSnapshot());
        return new Result(Outcome.REFRESHED, summarise(done.split().changed(), done.split().cleared(),
                done.applied().refreshed().missingLines().size(), done.applied().refreshed().extraTransactionIds().size(),
                done.applied().refreshed().relinked()));
    }

    /** The account's saved statement password (empty when none); null when the account is not this user's. */
    private Optional<String> savedPassword(UUID userId, UUID accountId) {
        Optional<Optional<String>> found = tx.execute(status -> {
            userFilterEnabler.enable();
            return accountRepository.findByIdAndUser_Id(accountId, userId).map(a -> Optional.ofNullable(
                    a.getBankDetails() != null ? a.getBankDetails().getStatementPassword()
                            : a.getCreditCardDetails() != null ? a.getCreditCardDetails().getStatementPassword() : null));
        });
        return found == null || found.isEmpty() ? null : found.get();
    }

    /** {@code changed=[..] cleared=[..] addRows=n extras=n relinked=n}: names and counts only, trimmed to 500 characters. */
    static String summarise(List<String> changed, List<String> cleared, int addRows, int extras, int relinked) {
        String s = "changed=[" + String.join(",", changed) + "] cleared=[" + String.join(",", cleared)
                + "] addRows=" + addRows + " extras=" + extras + " relinked=" + relinked;
        return s.length() > SUMMARY_MAX ? s.substring(0, SUMMARY_MAX) : s;
    }

    /** Stamps without ever throwing (a failed stamp only means the statement is looked at again next run). */
    private void stamp(UUID statementId, Result r) {
        try {
            String summary = r.summary() == null ? null
                    : r.summary().length() > SUMMARY_MAX ? r.summary().substring(0, SUMMARY_MAX) : r.summary();
            tx.executeWithoutResult(status -> statementRepository.markReparse(statementId, currentVersion,
                    clock.instant(), r.outcome().name(), summary));
        } catch (Exception e) {
            log.warn("Statement re-read could not stamp statement={}: {}", statementId, e.getClass().getSimpleName());
        }
    }
}
