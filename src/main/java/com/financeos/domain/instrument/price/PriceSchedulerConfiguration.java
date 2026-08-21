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

    public PriceSchedulerConfiguration(com.financeos.domain.job.JobService jobService) {
        this.jobService = jobService;
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
