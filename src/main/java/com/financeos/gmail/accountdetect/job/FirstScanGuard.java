package com.financeos.gmail.accountdetect.job;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lets one first (full-mailbox) account-detect scan run at a time in this server: the job pool has two workers and a
 * first scan takes minutes. A job that cannot take the guard is put back on the queue after a delay ({@link #later});
 * re-scans never take it. In memory only: a restart drops a pending re-queue, and the next successful Gmail sync
 * enqueues the scan again.
 */
@Component
public class FirstScanGuard {

    private UUID holder;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "account-detect-requeue");
        t.setDaemon(true);
        return t;
    });

    public synchronized boolean tryAcquire(UUID userId) {
        if (holder == null || holder.equals(userId)) {
            holder = userId;
            return true;
        }
        return false;
    }

    public synchronized void release(UUID userId) {
        if (userId.equals(holder)) holder = null;
    }

    public synchronized boolean isHeld() {
        return holder != null;
    }

    /** Runs {@code task} once after {@code delay} (used to re-queue a deferred first scan). */
    public void later(Runnable task, Duration delay) {
        scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
