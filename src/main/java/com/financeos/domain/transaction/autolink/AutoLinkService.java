package com.financeos.domain.transaction.autolink;

import com.financeos.core.security.UserContextHelper;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.domain.transaction.autolink.AutoLinkOutcome.Counts;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs every registered {@link AutoLinker} for one user, in order, each pass in its own transaction.
 * Never throws: a linker that fails is logged and the next one still runs. One run per user at a time; a
 * request that arrives while one is going only flags "run again", and the running thread does one more pass
 * when it finishes (no queue).
 */
@Service
public class AutoLinkService {

    private static final Logger log = LoggerFactory.getLogger(AutoLinkService.class);

    /**
     * Per suggestion kind: how many pairings were linked and how many suggestions are now stored.
     * {@code ran}: this call did the work (false when another run was in flight and only flagged a rerun).
     * {@code failed}: some linker or pairing failed, so the run was not clean.
     */
    public record RunResult(Map<SuggestionKind, Counts> byKind, boolean ran, boolean failed) {
        /** The call was skipped because a run for the user was already going. */
        public static final RunResult EMPTY = new RunResult(Map.of(), false, false);

        public static RunResult ranWith(Map<SuggestionKind, Counts> byKind) {
            return new RunResult(byKind, true, false);
        }

        public boolean clean() {
            return ran && !failed;
        }

        public int linked() {
            return byKind.values().stream().mapToInt(Counts::linked).sum();
        }

        public int suggested() {
            return byKind.values().stream().mapToInt(Counts::suggested).sum();
        }

        RunResult plus(RunResult other) {
            return new RunResult(AutoLinkOutcome.merge(byKind, other.byKind), ran || other.ran, failed || other.failed);
        }
    }

    private final List<AutoLinker> linkers;
    private final TransactionTemplate passTransaction;
    private final NotificationFeedService feed;

    private final Object gate = new Object();
    private final Set<UUID> running = new HashSet<>();
    private final Set<UUID> rerun = new HashSet<>();

    public AutoLinkService(List<AutoLinker> linkers, PlatformTransactionManager transactionManager,
                           NotificationFeedService feed) {
        this.linkers = linkers;
        this.feed = feed;
        this.passTransaction = new TransactionTemplate(transactionManager);
        this.passTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs for the user and, when something was linked automatically, writes one feed notification. */
    public RunResult run(UUID userId) {
        RunResult result = runGuarded(userId);
        if (result.linked() > 0) {
            notifyLinked(userId, result);
        }
        return result;
    }

    /** Runs for the user without any notification (the backfill writes its own summary). */
    public RunResult runQuietly(UUID userId) {
        return runGuarded(userId);
    }

    /** If a run for the user is going, flags one more run and returns true; otherwise does nothing. */
    public boolean requestRerunIfRunning(UUID userId) {
        synchronized (gate) {
            if (running.contains(userId)) {
                rerun.add(userId);
                return true;
            }
            return false;
        }
    }

    private RunResult runGuarded(UUID userId) {
        synchronized (gate) {
            if (!running.add(userId)) {
                rerun.add(userId);
                return RunResult.EMPTY;
            }
        }
        RunResult total = new RunResult(Map.of(), true, false);
        try {
            while (true) {
                total = total.plus(runOnce(userId));
                synchronized (gate) {
                    if (!rerun.remove(userId)) {
                        running.remove(userId);
                        return total;
                    }
                }
            }
        } catch (RuntimeException | Error e) {
            synchronized (gate) {
                running.remove(userId);
                rerun.remove(userId);
            }
            log.warn("Auto-link run failed for user {}: {}", userId, e.getMessage());
            return total.plus(new RunResult(Map.of(), true, true));
        }
    }

    private RunResult runOnce(UUID userId) {
        MDC.put("userId", userId.toString());
        try {
            return UserContextHelper.callAs(userId, () -> {
                Map<SuggestionKind, Counts> byKind = new EnumMap<>(SuggestionKind.class);
                boolean failed = false;
                for (AutoLinker linker : linkers) {
                    try {
                        AutoLinkOutcome outcome = passTransaction.execute(status -> linker.link(userId));
                        if (outcome != null) {
                            byKind = AutoLinkOutcome.merge(byKind, outcome.byKind());
                            failed |= outcome.partial();
                        }
                    } catch (Exception e) {
                        failed = true;
                        log.warn("Auto-linker {} failed for user {}: {}", linker.getClass().getSimpleName(), userId, e.getMessage(), e);
                    }
                }
                return new RunResult(byKind, true, failed);
            });
        } finally {
            MDC.remove("userId");
        }
    }

    // ---------------------------------------------------------------- notifications

    private void notifyLinked(UUID userId, RunResult result) {
        try {
            feed.record(userId, AppNotificationKind.AUTO_LINKED, "Linked " + result.linked() + " automatically: " + describe(result),
                    null, AppNotificationRefs.NONE, "auto-linked:" + UUID.randomUUID());
        } catch (RuntimeException e) {
            log.warn("Auto-link notification failed for user {}: {}", userId, e.getMessage());
        }
    }

    /** One feed row summing up the one-time history backfill. */
    public void notifyBackfilled(UUID userId, RunResult result) {
        if (result.linked() == 0 && result.suggested() == 0) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (result.linked() > 0) {
            parts.add("linked " + result.linked() + " automatically (" + describe(result) + ")");
        }
        if (result.suggested() > 0) {
            parts.add(result.suggested() + " to review");
        }
        String text = "Checked your history: " + String.join(", ", parts);
        try {
            feed.record(userId, AppNotificationKind.AUTO_LINKED, text, null, AppNotificationRefs.NONE,
                    "auto-linked-backfill:" + userId);
        } catch (RuntimeException e) {
            log.warn("Auto-link backfill notification failed for user {}: {}", userId, e.getMessage());
        }
    }

    /** "3 transfers, 1 card payment" for the linked counts. */
    static String describe(RunResult result) {
        List<String> parts = new ArrayList<>();
        int transfers = result.byKind().getOrDefault(SuggestionKind.TRANSFER, Counts.ZERO).linked();
        int payments = result.byKind().getOrDefault(SuggestionKind.CC_PAYMENT, Counts.ZERO).linked();
        int refunds = result.byKind().getOrDefault(SuggestionKind.REFUND, Counts.ZERO).linked();
        if (transfers > 0) {
            parts.add(transfers + (transfers == 1 ? " transfer" : " transfers"));
        }
        if (payments > 0) {
            parts.add(payments + (payments == 1 ? " card payment" : " card payments"));
        }
        if (refunds > 0) {
            parts.add(refunds + (refunds == 1 ? " refund" : " refunds"));
        }
        return String.join(", ", parts);
    }
}
