package com.financeos.gmail.ingest;

import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final GmailConnectionRepository connectionRepository;
    private final GmailIngestionService ingestionService;
    private final GmailIngestProperties ingestProperties;

    public IngestionScheduler(GmailConnectionRepository connectionRepository,
                              GmailIngestionService ingestionService,
                              GmailIngestProperties ingestProperties) {
        this.connectionRepository = connectionRepository;
        this.ingestionService = ingestionService;
        this.ingestProperties = ingestProperties;
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

        log.info("Starting scheduled Gmail ingestion run...");
        List<GmailConnection> activeConnections = connectionRepository.findByIsConnectedTrue();
        log.info("Found {} active Gmail connection(s) to process", activeConnections.size());

        for (GmailConnection connection : activeConnections) {
            try {
                log.info("Processing ingestion for user connection: {}", connection.getEmail());
                SyncSummary summary = ingestionService.syncConnection(connection);
                log.info("Completed ingestion for {}. Summary: {}", connection.getEmail(), summary);
            } catch (Exception e) {
                log.error("Failed to run ingestion for connection: " + connection.getEmail(), e);
            }
        }
        log.info("Scheduled Gmail ingestion run completed.");
    }
}
