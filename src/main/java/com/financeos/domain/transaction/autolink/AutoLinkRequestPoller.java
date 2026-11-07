package com.financeos.domain.transaction.autolink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker side of the api-to-worker hand-off: claims requests written by api nodes and runs them through the same
 * dispatch as an in-process event. Only fires where scheduling is enabled (not app.role=api). A dispatch that throws
 * puts the request back in the table (merged with anything that arrived since), so it is retried next tick.
 */
@Component
public class AutoLinkRequestPoller {

    private static final Logger log = LoggerFactory.getLogger(AutoLinkRequestPoller.class);

    private final AutoLinkRequestStore store;
    private final TransactionsIngestedListener listener;

    private final boolean enabled;

    public AutoLinkRequestPoller(AutoLinkRequestStore store, TransactionsIngestedListener listener,
                                 @Value("${autolink.enabled:true}") boolean enabled) {
        this.store = store;
        this.listener = listener;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${autolink.requests.poll-ms:5000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            poll();
        } catch (Exception e) {
            log.warn("Auto-link request poll failed: {}", e.getMessage());
        }
    }

    /** Claims and dispatches every pending request; returns how many were dispatched. */
    int poll() {
        int dispatched = 0;
        for (AutoLinkRequestStore.Pending p : store.pending()) {
            if (!store.claim(p)) {
                continue;   // widened or taken meanwhile; the next tick re-reads it
            }
            try {
                listener.dispatch(p.userId(), p.scope());
                dispatched++;
            } catch (RuntimeException e) {
                store.request(p.userId(), p.scope());
                log.warn("Auto-link dispatch failed for user {}; request restored: {}", p.userId(), e.getMessage());
            }
        }
        return dispatched;
    }
}
