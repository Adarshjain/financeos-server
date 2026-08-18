package com.financeos.gmail.ingest;

import com.financeos.core.observability.Events;
import com.financeos.core.observability.JobRunContext;
import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final GmailConnectionRepository connectionRepository;
    private final GmailIngestionService ingestionService;
    private final GmailIngestProperties ingestProperties;
    private final ObservabilityMetrics observabilityMetrics;

    public IngestionScheduler(GmailConnectionRepository connectionRepository,
                              GmailIngestionService ingestionService,
                              GmailIngestProperties ingestProperties,
                              ObservabilityMetrics observabilityMetrics) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.ingestProperties = ingestProperties;
        this.observabilityMetrics = observabilityMetrics;
    }

    /**
     * Periodic cron task that syncs Gmail connections and processes transactions.
     * Defaults to running every 2 hours from 10:00 to 22:00 in Asia/Kolkata (IST).
     */
    @Scheduled(cron = "${gmail.ingest.cron:0 0 10-22/2 * * *}", zone = "${gmail.ingest.zone:Asia/Kolkata}")
    public void runIngestion() {
        if (!ingestProperties.isEnabled()) {
            log.info("Gmail Ingestion is disabled via configuration properties");
            return;
        }

        try (JobRunContext ctx = JobRunContext.start()) {
            String jobRunId = ctx.getJobRunId();
            long startMs = System.currentTimeMillis();

            log.info("Job started: jobName=gmail-ingest, trigger=cron",
                    StructuredArguments.keyValue("event", Events.JOB_STARTED),
                    StructuredArguments.keyValue("jobName", "gmail-ingest"),
                    StructuredArguments.keyValue("jobRunId", jobRunId),
                    StructuredArguments.keyValue("trigger", "cron"));

            int totalProcessed = 0;
            int totalSucceeded = 0;
            int totalSkipped = 0;
            int totalFailed = 0;
            Map<String, Integer> skipReasons = new HashMap<>();
            Map<String, Integer> failReasons = new HashMap<>();

            try {
                List<GmailConnection> activeConnections = connectionRepository.findByIsConnectedTrue();
                log.info("Found {} active Gmail connection(s) to process", activeConnections.size());

                for (GmailConnection connection : activeConnections) {
                    try {
                        log.info("Processing ingestion for user connection: {}", connection.getEmail());
                        SyncSummary summary = ingestionService.syncConnection(connection);
                        totalProcessed += summary.fetched();
                        totalSucceeded += summary.created() + summary.reconciled();
                        totalSkipped += summary.skipped();
                        totalFailed += summary.failed();

                        if (summary.skipped() > 0) {
                            skipReasons.merge("already-processed-or-duplicate", summary.skipped(), Integer::sum);
                        }
                        if (summary.failed() > 0) {
                            failReasons.merge("parse-or-validation-error", summary.failed(), Integer::sum);
                        }

                        log.info("Completed ingestion for {}: fetched={}, created={}, skipped={}, failed={}, reconciled={}",
                                connection.getEmail(), summary.fetched(), summary.created(), summary.skipped(), summary.failed(), summary.reconciled(),
                                StructuredArguments.keyValue("email", connection.getEmail()),
                                StructuredArguments.keyValue("fetched", summary.fetched()),
                                StructuredArguments.keyValue("created", summary.created()),
                                StructuredArguments.keyValue("skipped", summary.skipped()),
                                StructuredArguments.keyValue("failed", summary.failed()),
                                StructuredArguments.keyValue("reconciled", summary.reconciled()));
                    } catch (Exception e) {
                        totalFailed++;
                        String errKey = e.getClass().getSimpleName();
                        failReasons.merge(errKey, 1, Integer::sum);
                        log.error("Failed to run ingestion for connection: email={}", connection.getEmail(),
                                StructuredArguments.keyValue("email", connection.getEmail()),
                                e);
                    }
                }

                long durationMs = System.currentTimeMillis() - startMs;
                observabilityMetrics.recordJobSuccess("gmail-ingest");

                log.info("Job completed: jobName=gmail-ingest, durationMs={}", durationMs,
                        StructuredArguments.keyValue("event", Events.JOB_COMPLETED),
                        StructuredArguments.keyValue("jobName", "gmail-ingest"),
                        StructuredArguments.keyValue("jobRunId", jobRunId),
                        StructuredArguments.keyValue("durationMs", durationMs),
                        StructuredArguments.keyValue("processed", totalProcessed),
                        StructuredArguments.keyValue("succeeded", totalSucceeded),
                        StructuredArguments.keyValue("skipped", totalSkipped),
                        StructuredArguments.keyValue("failed", totalFailed),
                        StructuredArguments.keyValue("skipReasons", skipReasons),
                        StructuredArguments.keyValue("failReasons", failReasons));
            } catch (Exception e) {
                long durationMs = System.currentTimeMillis() - startMs;
                log.error("Job failed: jobName=gmail-ingest, durationMs={}, stage=runIngestion", durationMs,
                        StructuredArguments.keyValue("event", Events.JOB_FAILED),
                        StructuredArguments.keyValue("jobName", "gmail-ingest"),
                        StructuredArguments.keyValue("jobRunId", jobRunId),
                        StructuredArguments.keyValue("durationMs", durationMs),
                        StructuredArguments.keyValue("stage", "runIngestion"),
                        StructuredArguments.keyValue("errorClass", e.getClass().getName()),
                        e);
            }
        }
    }
}
