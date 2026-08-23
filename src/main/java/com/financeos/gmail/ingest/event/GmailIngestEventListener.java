package com.financeos.gmail.ingest.event;

import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.handlers.GmailSyncPayload;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import com.financeos.gmail.ingest.GmailIngestProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class GmailIngestEventListener {

    private static final Logger log = LoggerFactory.getLogger(GmailIngestEventListener.class);

    private final GmailProcessedMessageRepository processedMessageRepository;
    private final GmailConnectionRepository connectionRepository;
    private final JobService jobService;
    private final GmailIngestProperties ingestProperties;

    public GmailIngestEventListener(GmailProcessedMessageRepository processedMessageRepository,
                                   GmailConnectionRepository connectionRepository,
                                   JobService jobService,
                                   GmailIngestProperties ingestProperties) {
        this.processedMessageRepository = processedMessageRepository;
        this.connectionRepository = connectionRepository;
        this.jobService = jobService;
        this.ingestProperties = ingestProperties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAccountIngestChanged(AccountIngestChangedEvent event) {
        log.info("Handling AccountIngestChangedEvent for user {}, last4 {}, date {}", event.userId(), event.last4(), event.ingestFromDate());

        if (event.last4() != null && !event.last4().isBlank() && event.ingestFromDate() != null) {
            Instant minInstant = event.ingestFromDate()
                    .minusDays(ingestProperties.getDateWindowDays())
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();

            List<GmailProcessedMessage> parked = processedMessageRepository.findParkedForReactivation(
                    event.userId(),
                    event.last4(),
                    List.of(GmailProcessedStatus.UNRESOLVED_ACCOUNT, GmailProcessedStatus.ACCOUNT_NOT_OPTED_IN),
                    minInstant
            );

            if (!parked.isEmpty()) {
                log.info("Re-activating {} parked rows for last4 {}", parked.size(), event.last4());
                for (GmailProcessedMessage gpm : parked) {
                    gpm.setStatus(GmailProcessedStatus.DISCOVERED);
                    gpm.setAttemptCount(0);
                    gpm.setNextRetryAt(null);
                    gpm.setError(null);
                }
                processedMessageRepository.saveAll(parked);
            }
        }

        enqueueSyncJobsForUser(event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSenderIngestChanged(SenderIngestChangedEvent event) {
        log.info("Handling SenderIngestChangedEvent for user {}", event.userId());
        enqueueSyncJobsForUser(event.userId());
    }

    private void enqueueSyncJobsForUser(java.util.UUID userId) {
        List<GmailConnection> connections = connectionRepository.findByUserId(userId);
        for (GmailConnection conn : connections) {
            if (Boolean.TRUE.equals(conn.getIsConnected())) {
                try {
                    jobService.enqueue(
                            userId,
                            JobType.GMAIL_SYNC,
                            JobTrigger.CRON,
                            new GmailSyncPayload(conn.getId()),
                            null,
                            conn.getId().toString()
                    );
                } catch (Exception e) {
                    log.warn("Failed to enqueue GMAIL_SYNC job for connection {}: {}", conn.getId(), e.getMessage());
                }
            }
        }
    }
}
