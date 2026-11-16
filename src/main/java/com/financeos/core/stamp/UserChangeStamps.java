package com.financeos.core.stamp;

import com.financeos.core.time.AppTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A per-user change counter (table {@code user_change_stamp}) that moves whenever the user's data changes,
 * whichever JVM (API or worker) made the change. Caches keyed by user read {@link #version} to know whether
 * what they hold is still current.
 *
 * <p>{@link #markChanged} defers the bump to just after the surrounding transaction commits (once per user
 * per transaction; nothing on rollback). The bump runs in its own transaction with plain SQL, never fails the
 * caller (a failure is logged as a warning) and cannot re-trigger the change listener.
 */
@Slf4j
@Component
public class UserChangeStamps {

    private static final Object PENDING_KEY = new Object();

    /** Most users waiting for a bump at once; beyond this a bump is dropped (the memo's max age bounds the staleness). */
    static final int MAX_PENDING = 1000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    private final ExecutorService executor;
    /** Users with a bump queued and not yet started: a second change for one of them rides on that bump. */
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    /**
     * Bumps run on one background thread, so the committing request never waits for (or needs) a second
     * connection. {@code app.user-change-stamp.async=false} runs them on the committing thread (tests).
     */
    @Autowired
    public UserChangeStamps(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            @Value("${app.user-change-stamp.async:true}") boolean async) {
        this(jdbc, transactionManager, async ? newExecutor() : null);
    }

    /** {@code executor} null runs bumps on the calling thread. */
    UserChangeStamps(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ExecutorService executor) {
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executor = executor;
    }

    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(MAX_PENDING),
                runnable -> {
                    Thread t = new Thread(runnable, "user-change-stamp");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PreDestroy
    void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** After a commit: queue a bump for the user (coalesced; dropped with a warning when the queue is full). Never blocks. */
    private void enqueue(UUID userId) {
        if (executor == null) {
            bumpNow(userId);
            return;
        }
        if (queued.size() >= MAX_PENDING || !queued.add(userId)) {
            if (!queued.contains(userId)) {
                log.warn("User change stamp: queue full, bump for {} dropped", userId);
            }
            return;
        }
        try {
            executor.execute(() -> {
                queued.remove(userId); // a change landing from here on queues a fresh bump
                bumpNow(userId);
            });
        } catch (RejectedExecutionException e) {
            queued.remove(userId);
            log.warn("User change stamp: bump for {} not queued", userId, e);
        }
    }

    /** The user's current stamp (0 when never bumped); -1 when it cannot be read, which no cached entry matches. */
    public long version(UUID userId) {
        try {
            List<Long> rows = jdbc.queryForList("SELECT version FROM user_change_stamp WHERE user_id = ?", Long.class,
                    userId.toString());
            return rows.isEmpty() ? 0L : rows.get(0);
        } catch (RuntimeException e) {
            log.warn("User change stamp: read failed for {}", userId, e);
            return -1L;
        }
    }

    /** Records that the user's data changed in the current transaction; bumps after it commits (at once without one). */
    @SuppressWarnings("unchecked")
    public void markChanged(UUID userId) {
        if (userId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            bumpNow(userId);
            return;
        }
        Set<UUID> pending = (Set<UUID>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending == null) {
            Set<UUID> fresh = new LinkedHashSet<>();
            pending = fresh;
            TransactionSynchronizationManager.bindResource(PENDING_KEY, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    fresh.forEach(UserChangeStamps.this::enqueue);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                }
            });
        }
        pending.add(userId);
    }

    /** Bumps now, in its own transaction; never throws. */
    public void bumpNow(UUID userId) {
        try {
            ownTransaction.executeWithoutResult(status -> bump(userId.toString()));
        } catch (RuntimeException e) {
            log.warn("User change stamp: bump failed for {}", userId, e);
        }
    }

    private void bump(String userId) {
        OffsetDateTime now = OffsetDateTime.now(AppTime.zone());
        if (update(userId, now)) {
            return;
        }
        try {
            // Only for a user that exists (a deleted account has nothing to stamp).
            jdbc.update("INSERT INTO user_change_stamp (user_id, changed_at, version) "
                    + "SELECT id, ?, 1 FROM users WHERE id = ?", now, userId);
        } catch (DuplicateKeyException raced) {
            update(userId, now);
        }
    }

    private boolean update(String userId, OffsetDateTime now) {
        return jdbc.update("UPDATE user_change_stamp SET version = version + 1, changed_at = ? WHERE user_id = ?",
                now, userId) > 0;
    }
}
