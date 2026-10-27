package com.financeos.domain.instrument.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Monthly prune of old feed prices (see {@link PriceHistoryService#cleanup()}). */
@Component
@ConditionalOnProperty(name = "price.enabled", havingValue = "true", matchIfMissing = true)
public class PriceHistoryCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryCleanupScheduler.class);

    private final PriceHistoryService historyService;

    public PriceHistoryCleanupScheduler(PriceHistoryService historyService) {
        this.historyService = historyService;
    }

    @Scheduled(cron = "${price.history.cleanup-cron}", zone = "${price.timezone}")
    public void runCleanup() {
        try {
            historyService.cleanup();
        } catch (Exception e) {
            log.error("Price history cleanup failed", e);
        }
    }
}
