package com.financeos.domain.transaction.autolink;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs auto-linking in the background once the ingesting transaction has committed, on the existing
 * {@code jobExecutor}. {@code fallbackExecution}: a Gmail batch commits message by message and publishes
 * with no transaction open, so the event must still fire. A full queue only skips this run; the next
 * ingest (or the one-time backfill) links what it missed.
 */
@Component
public class TransactionsIngestedListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionsIngestedListener.class);

    private final AutoLinkService autoLinkService;
    private final Executor executor;
    /** Users with a run queued but not started; a further event for them adds nothing. */
    private final java.util.Set<UUID> pending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TransactionsIngestedListener(AutoLinkService autoLinkService, @Qualifier("jobExecutor") Executor executor) {
        this.autoLinkService = autoLinkService;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransactionsIngested(TransactionsIngestedEvent event) {
        UUID userId = event.userId();
        if (userId == null) {
            return;
        }
        // Already queued: that run reads fresh data when it starts. Already running: flag one more run.
        if (pending.contains(userId) || autoLinkService.requestRerunIfRunning(userId)) {
            return;
        }
        pending.add(userId);
        try {
            executor.execute(() -> {
                pending.remove(userId);
                autoLinkService.run(userId);
            });
        } catch (RejectedExecutionException e) {
            pending.remove(userId);
            log.info("Auto-link queue is full; skipped a run for user {}", userId);
        }
    }
}
