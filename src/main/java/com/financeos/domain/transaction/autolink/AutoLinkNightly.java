package com.financeos.domain.transaction.autolink;

import java.util.ArrayList;
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
 * Nightly full pass that heals what scoped runs can miss: a run dropped because the queue was full, one that failed
 * or was partial, and edits or unlinks that publish no ingest event. Every user with a scoped run (or a dropped one)
 * since their last full pass is run in full, one after another, quietly. Default 03:30 Asia/Kolkata. Off with
 * {@code autolink.enabled=false}.
 */
@Component
public class AutoLinkNightly {

    private static final Logger log = LoggerFactory.getLogger(AutoLinkNightly.class);

    private final AutoLinkService autoLinkService;
    private final Executor executor;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AutoLinkNightly(AutoLinkService autoLinkService, @Qualifier("jobExecutor") Executor executor,
                           @Value("${autolink.enabled:true}") boolean enabled) {
        this.autoLinkService = autoLinkService;
        this.executor = executor;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${autolink.nightly.cron:0 30 3 * * *}", zone = "${autolink.nightly.zone:Asia/Kolkata}")
    public void tick() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runTouched();
                } catch (Exception e) {
                    log.warn("Nightly auto-link pass failed: {}", e.getMessage());
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
            log.info("Nightly auto-link pass skipped: executor queue is full");
        }
    }

    /** Full pass for every user touched since their last one; returns how many ran clean. A user whose pass is not clean stays marked. */
    public int runTouched() {
        List<UUID> users = new ArrayList<>(autoLinkService.touchedUsers());
        int clean = 0;
        for (UUID userId : users) {
            try {
                AutoLinkService.RunResult result = autoLinkService.runQuietly(userId);
                if (result.clean()) {
                    clean++;
                } else {
                    autoLinkService.markTouched(userId);
                }
            } catch (Exception e) {
                autoLinkService.markTouched(userId);
                log.warn("Nightly auto-link pass failed for user {}: {}", userId, e.getMessage());
            }
        }
        if (!users.isEmpty()) {
            log.info("Nightly auto-link pass: {} user(s), {} clean", users.size(), clean);
        }
        return clean;
    }
}
