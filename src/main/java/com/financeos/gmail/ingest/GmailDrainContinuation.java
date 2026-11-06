package com.financeos.gmail.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobService;
import com.financeos.domain.job.JobStatus;
import com.financeos.domain.job.JobTrigger;
import com.financeos.domain.job.JobType;
import com.financeos.domain.job.handlers.GmailSyncPayload;
import com.financeos.domain.notification.job.JobFinishedEvent;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps a big Gmail backlog draining: after a successful GMAIL_SYNC, if drainable mails remain and the run made
 * progress, enqueues another sync instead of waiting for the next cron slot. Uses the scheduler's dedupKey (the
 * connection id) so a continuation and the cron never run together.
 */
@Component
public class GmailDrainContinuation {

    private static final Logger log = LoggerFactory.getLogger(GmailDrainContinuation.class);

    private final JobRepository jobRepository;
    private final GmailConnectionRepository connectionRepository;
    private final GmailProcessedMessageRepository processedMessageRepository;
    private final JobService jobService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public GmailDrainContinuation(JobRepository jobRepository,
                                  GmailConnectionRepository connectionRepository,
                                  GmailProcessedMessageRepository processedMessageRepository,
                                  JobService jobService,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.connectionRepository = connectionRepository;
        this.processedMessageRepository = processedMessageRepository;
        this.jobService = jobService;
        this.objectMapper = objectMapper;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onJobFinished(JobFinishedEvent event) {
        if (event.type() != JobType.GMAIL_SYNC || event.status() != JobStatus.SUCCEEDED) {
            return;
        }
        UUID previous = UserContext.getCurrentUserId();
        if (previous == null && event.userId() != null) {
            UserContext.setCurrentUserId(event.userId());
        }
        try {
            continueDrain(event);
        } catch (Exception e) {
            log.warn("Gmail drain continuation failed for job {}: {}", event.jobId(), e.getMessage());
        } finally {
            if (previous == null) {
                UserContext.clear();
            }
        }
    }

    private void continueDrain(JobFinishedEvent event) throws Exception {
        Job job = jobRepository.findById(event.jobId()).orElse(null);
        if (job == null || job.getPayload() == null) {
            return;
        }
        UUID connectionId = objectMapper.readValue(job.getPayload(), GmailSyncPayload.class).connectionId();
        Optional<GmailConnection> connection = connectionRepository.findById(connectionId);
        if (connection.isEmpty()
                || !Boolean.TRUE.equals(connection.get().getIsConnected())
                || connection.get().getAuthFailedAt() != null) {
            return;
        }
        // Progress guard: a run that processed nothing would only loop on rows that fail every time.
        if (processedCount(job.getResult()) <= 0) {
            return;
        }
        long pending = processedMessageRepository.countDrainable(connectionId, Instant.now());
        if (pending <= 0) {
            return;
        }
        log.info("Gmail drain continues for connection {}: {} pending", connectionId, pending);
        // REQUIRES_NEW: this runs after the finished job's transaction committed, so enqueue needs its own.
        txTemplate.executeWithoutResult(s -> jobService.enqueue(
                event.userId(),
                JobType.GMAIL_SYNC,
                JobTrigger.CRON,
                new GmailSyncPayload(connectionId),
                null,
                connectionId.toString()));
    }

    private int processedCount(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode node = objectMapper.readTree(resultJson).get("processed");
            return node == null ? 0 : node.asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }
}
