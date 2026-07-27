package com.financeos.domain.instrument.price;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Optional;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "price.enabled", havingValue = "true", matchIfMissing = true)
public class PriceSchedulerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PriceSchedulerConfiguration.class);

    private final PriceRefreshService priceRefreshService;

    public PriceSchedulerConfiguration(PriceRefreshService priceRefreshService) {
        this.priceRefreshService = priceRefreshService;
    }

    @Scheduled(cron = "${price.refresh-cron}", zone = "${price.timezone}")
    public void runDailyPriceRefresh() {
        log.info("Starting daily automated price refresh job...");
        try {
            PriceRefreshResult result = priceRefreshService.refresh(Optional.empty());
            log.info("Completed daily price refresh: refreshed={}, skipped={}, failed={}",
                    result.refreshed(), result.skipped(), result.failed().size());
        } catch (Exception e) {
            log.error("Daily price refresh scheduled job failed", e);
        }
    }
}
