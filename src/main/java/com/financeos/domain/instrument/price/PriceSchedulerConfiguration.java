package com.financeos.domain.instrument.price;

import com.financeos.core.observability.Events;
import com.financeos.core.observability.JobRunContext;
import com.financeos.core.observability.ObservabilityMetrics;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "price.enabled", havingValue = "true", matchIfMissing = true)
public class PriceSchedulerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PriceSchedulerConfiguration.class);

    private final com.financeos.domain.job.JobService jobService;
    @org.springframework.beans.factory.annotation.Value("${price.timezone:Asia/Kolkata}")
    private String zone;

    private final PriceRefreshService priceRefreshService;
    private final AmfiFeedClient amfiFeedClient;

    public PriceSchedulerConfiguration(com.financeos.domain.job.JobService jobService,
                                       PriceRefreshService priceRefreshService,
                                       AmfiFeedClient amfiFeedClient) {
        this.jobService = jobService;
        this.priceRefreshService = priceRefreshService;
        this.amfiFeedClient = amfiFeedClient;
    }

    /** Live Yahoo prices during the NSE session; the gate inside the service trims the cron's wider hours. */
    @Scheduled(cron = "${price.intraday-cron}", zone = "${price.timezone}")
    public void runIntradayPriceRefresh() {
        try {
            int written = priceRefreshService.refreshIntraday();
            if (written > 0) {
                log.info("Intraday price refresh wrote {} Yahoo prices", written);
            }
        } catch (Exception e) {
            log.error("Intraday price refresh failed", e);
        }
    }

    /** Picks up the night's AMFI NAVs. */
    @Scheduled(cron = "${price.amfi-refresh-cron}", zone = "${price.timezone}")
    public void runAmfiRefresh() {
        refreshAmfi("night");
    }

    /** Morning retry for NAVs AMFI published late. */
    @Scheduled(cron = "${price.amfi-retry-cron}", zone = "${price.timezone}")
    public void runAmfiRetry() {
        refreshAmfi("retry");
    }

    private void refreshAmfi(String run) {
        try {
            // AmfiFeedClient.ensureLoaded() has no TTL: the explicit load() refreshes the NAV cache.
            amfiFeedClient.load();
            int written = priceRefreshService.refreshSource(com.financeos.domain.instrument.PriceSource.AMFI);
            log.info("AMFI {} refresh wrote {} NAVs", run, written);
        } catch (Exception e) {
            log.error("AMFI {} refresh failed", run, e);
        }
    }

    @Scheduled(cron = "${price.refresh-cron}", zone = "${price.timezone}")
    public void runDailyPriceRefresh() {
        String dedupKey = "cron-" + java.time.LocalDate.now(java.time.ZoneId.of(zone));
        com.financeos.domain.job.Job job = jobService.enqueue(
                null,
                com.financeos.domain.job.JobType.PRICE_REFRESH,
                com.financeos.domain.job.JobTrigger.CRON,
                new com.financeos.domain.job.handlers.PriceRefreshPayload(null),
                null,
                dedupKey
        );
        log.info("Enqueued price refresh cron job: jobId={}, dedupKey={}", job.getId(), dedupKey);
    }
}
