package com.financeos.domain.transaction.autolink;

import com.financeos.core.role.AppRole;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
    private final boolean enabled;
    private final AppRole role;
    private final AutoLinkRequestStore requestStore;
    /** Users with a run queued but not started, with the union of the scopes of their events so far. */
    private final java.util.Map<UUID, LinkScope> pending = new java.util.concurrent.ConcurrentHashMap<>();

    public TransactionsIngestedListener(AutoLinkService autoLinkService, Executor executor) {
        this(autoLinkService, executor, true);
    }

    @Autowired
    public TransactionsIngestedListener(AutoLinkService autoLinkService, @Qualifier("jobExecutor") Executor executor,
                                       @Value("${autolink.enabled:true}") boolean enabled, AppRole role,
                                       AutoLinkRequestStore requestStore) {
        this.autoLinkService = autoLinkService;
        this.executor = executor;
        this.enabled = enabled;
        this.role = role;
        this.requestStore = requestStore;
    }

    public TransactionsIngestedListener(AutoLinkService autoLinkService, Executor executor, boolean enabled) {
        this(autoLinkService, executor, enabled, AppRole.ALL, null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransactionsIngested(TransactionsIngestedEvent event) {
        UUID userId = event.userId();
        if (!enabled || userId == null) {
            return;
        }
        if (role == AppRole.API) {
            requestStore.request(userId, event.scope());   // the worker's poller runs it; the api node never links
            return;
        }
        dispatch(userId, event.scope());
    }

    /** Runs (or queues, or widens a queued run of) auto-link for the user in this JVM. Shared by events and the request poller. */
    void dispatch(UUID userId, LinkScope scope) {
        // Already queued: widen that run's scope (it reads fresh data when it starts). Already running: flag one more run.
        if (pending.computeIfPresent(userId, (k, queued) -> queued.union(scope)) != null) {
            return;
        }
        boolean rerunFlagged = scope.isAll() ? autoLinkService.requestRerunIfRunning(userId)
                : autoLinkService.requestRerunIfRunning(userId, scope);
        if (rerunFlagged) {
            return;
        }
        pending.put(userId, scope);
        try {
            executor.execute(() -> {
                LinkScope toRun = pending.remove(userId);
                if (toRun == null || toRun.isAll()) {
                    autoLinkService.run(userId);
                } else {
                    autoLinkService.run(userId, toRun);
                }
            });
        } catch (RejectedExecutionException e) {
            pending.remove(userId);
            autoLinkService.markTouched(userId);   // the nightly full pass picks up what this run would have linked
            log.info("Auto-link queue is full; skipped a run for user {}; left for the nightly pass", userId);
        }
    }
}
