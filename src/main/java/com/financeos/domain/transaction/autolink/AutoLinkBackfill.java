package com.financeos.domain.transaction.autolink;

import com.financeos.domain.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * One-time history backfill: runs auto-linking once for every user whose {@code auto_link_backfilled_at}
 * is still null, one user at a time. The marker is stamped (and the summary notification written) only after
 * a clean run that actually ran; a failed run, or one skipped because another run for that user was in
 * flight, leaves it null and is retried on the next tick. Starts shortly after boot and then repeats hourly,
 * which also covers users created later. The scheduled method only hands the work to {@code jobExecutor}.
 */
@Component
public class AutoLinkBackfill {

    private static final Logger log = LoggerFactory.getLogger(AutoLinkBackfill.class);

    private final UserRepository userRepository;
    private final AutoLinkService autoLinkService;
    private final Executor executor;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AutoLinkBackfill(UserRepository userRepository, AutoLinkService autoLinkService,
                            @Qualifier("jobExecutor") Executor executor,
                            @Value("${autolink.backfill.enabled:true}") boolean enabled) {
        this.userRepository = userRepository;
        this.autoLinkService = autoLinkService;
        this.executor = executor;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${autolink.backfill.initial-delay:PT30S}", fixedDelayString = "${autolink.backfill.delay:PT1H}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runPending();
                } catch (Exception e) {
                    log.warn("Auto-link backfill failed: {}", e.getMessage());
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
            log.info("Auto-link backfill skipped: executor queue is full");
        }
    }

    /** Returns how many users were backfilled. */
    public int runPending() {
        List<UUID> pending = userRepository.findIdsPendingAutoLinkBackfill();
        if (pending.isEmpty()) {
            return 0;
        }
        log.info("Auto-link backfill: {} user(s) pending", pending.size());
        int done = 0;
        for (UUID userId : pending) {
            try {
                AutoLinkService.RunResult result = autoLinkService.runQuietly(userId);
                if (!result.clean()) {
                    log.info("Auto-link backfill for user {} not clean (ran={}, failed={}); will retry", userId, result.ran(), result.failed());
                    continue;
                }
                userRepository.markAutoLinkBackfilled(userId, Instant.now());
                autoLinkService.notifyBackfilled(userId, result);
                done++;
                if (result.linked() > 0 || result.suggested() > 0) {
                    log.info("Auto-link backfill done for user {}: linked={}, suggested={}", userId, result.linked(), result.suggested());
                }
            } catch (Exception e) {
                log.warn("Auto-link backfill failed for user {}: {}", userId, e.getMessage());
            }
        }
        return done;
    }
}
