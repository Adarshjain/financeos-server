package com.financeos.domain.instrument.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Auto-fetches the latest price for instruments touched by a tradebook change.
 *
 * Runs on AFTER_COMMIT (synchronously, on the request thread) so:
 *  - it only fires for trades/imports that actually persisted, and
 *  - the price row exists before the HTTP response returns, so the client's post-mutation
 *    refresh shows the new price without a manual "Refresh Prices" click.
 *
 * Each instrument is refreshed independently and failures are swallowed: a Yahoo/AMFI outage
 * must never fail the (already committed) trade or block the other instruments.
 */
@Component
public class PriceRefreshEventListener {

    private static final Logger log = LoggerFactory.getLogger(PriceRefreshEventListener.class);

    private final PriceRefreshService priceRefreshService;

    public PriceRefreshEventListener(PriceRefreshService priceRefreshService) {
        this.priceRefreshService = priceRefreshService;
    }

    // REQUIRES_NEW: the trade/import transaction has already committed by AFTER_COMMIT, so a fresh
    // transaction is needed for the price writes to actually persist (joining the completed one
    // would silently discard them).
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPriceRefresh(PriceRefreshEvent event) {
        if (event.instrumentIds() == null || event.instrumentIds().isEmpty()) {
            return;
        }
        for (UUID instrumentId : event.instrumentIds()) {
            try {
                priceRefreshService.refresh(Optional.of(instrumentId));
            } catch (Exception e) {
                log.warn("Auto price refresh failed for instrument {}: {}", instrumentId, e.getMessage());
            }
        }
    }
}
