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
    private final GmailIngestProperties ingestProperties;
    private final com.financeos.domain.job.JobService jobService;

    public IngestionScheduler(GmailConnectionRepository connectionRepository,
                              GmailIngestProperties ingestProperties,
                              com.financeos.domain.job.JobService jobService) {
        this.connectionRepository = connectionRepository;
        this.ingestProperties = ingestProperties;
        this.jobService = jobService;
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

        List<GmailConnection> activeConnections = connectionRepository.findByIsConnectedTrue();
        log.info("Found {} active Gmail connection(s) to enqueue for ingestion", activeConnections.size());

        for (GmailConnection connection : activeConnections) {
            try {
                com.financeos.domain.job.Job job = jobService.enqueue(
                        connection.getUser().getId(),
                        com.financeos.domain.job.JobType.GMAIL_SYNC,
                        com.financeos.domain.job.JobTrigger.CRON,
                        new com.financeos.domain.job.handlers.GmailSyncPayload(connection.getId()),
                        null,
                        connection.getId().toString()
                );
                log.info("Enqueued Gmail sync cron job for connection {}: jobId={}", connection.getId(), job.getId());
            } catch (Exception e) {
                log.error("Failed to enqueue Gmail sync job for connection {}", connection.getId(), e);
            }
        }
    }
}
