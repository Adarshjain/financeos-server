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

    private final PriceRefreshService priceRefreshService;
    private final AmfiFeedClient amfiFeedClient;
    private final ObservabilityMetrics observabilityMetrics;

    public PriceSchedulerConfiguration(PriceRefreshService priceRefreshService,
                                       AmfiFeedClient amfiFeedClient,
                                       ObservabilityMetrics observabilityMetrics) {
        this.priceRefreshService = priceRefreshService;
        this.amfiFeedClient = amfiFeedClient;
        this.observabilityMetrics = observabilityMetrics;
    }

    @Scheduled(cron = "${price.refresh-cron}", zone = "${price.timezone}")
    public void runDailyPriceRefresh() {
        try (JobRunContext ctx = JobRunContext.start()) {
            String jobRunId = ctx.getJobRunId();
            long startMs = System.currentTimeMillis();

            log.info("Job started: jobName=price-refresh, trigger=cron",
                    StructuredArguments.keyValue("event", Events.JOB_STARTED),
                    StructuredArguments.keyValue("jobName", "price-refresh"),
                    StructuredArguments.keyValue("jobRunId", jobRunId),
                    StructuredArguments.keyValue("trigger", "cron"));

            try {
                amfiFeedClient.load();
                PriceRefreshResult result = priceRefreshService.refresh(Optional.empty());
                long durationMs = System.currentTimeMillis() - startMs;

                observabilityMetrics.recordJobSuccess("price-refresh");

                Map<String, Integer> failReasons = new HashMap<>();
                if (result.failed() != null) {
                    for (PriceRefreshResult.FailedItem item : result.failed()) {
                        String reason = item.reason() != null ? item.reason() : "unknown-error";
                        failReasons.merge(reason, 1, Integer::sum);
                    }
                }

                log.info("Job completed: jobName=price-refresh, durationMs={}", durationMs,
                        StructuredArguments.keyValue("event", Events.JOB_COMPLETED),
                        StructuredArguments.keyValue("jobName", "price-refresh"),
                        StructuredArguments.keyValue("jobRunId", jobRunId),
                        StructuredArguments.keyValue("durationMs", durationMs),
                        StructuredArguments.keyValue("processed", result.refreshed() + result.skipped() + (result.failed() != null ? result.failed().size() : 0)),
                        StructuredArguments.keyValue("succeeded", result.refreshed()),
                        StructuredArguments.keyValue("skipped", result.skipped()),
                        StructuredArguments.keyValue("failed", result.failed() != null ? result.failed().size() : 0),
                        StructuredArguments.keyValue("failReasons", failReasons));
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startMs;
                log.error("Job failed: jobName=price-refresh, durationMs={}, stage=runDailyPriceRefresh", durationMs,
                        StructuredArguments.keyValue("event", Events.JOB_FAILED),
                        StructuredArguments.keyValue("jobName", "price-refresh"),
                        StructuredArguments.keyValue("jobRunId", jobRunId),
                        StructuredArguments.keyValue("durationMs", durationMs),
                        StructuredArguments.keyValue("stage", "runDailyPriceRefresh"),
                        StructuredArguments.keyValue("errorClass", e.getClass().getName()),
                        e);
            }
        }
    }
}
