package com.financeos.gmail.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.job.Job;
import com.financeos.domain.job.JobArtifactRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

class GmailDrainContinuationTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final GmailConnectionRepository connectionRepository = mock(GmailConnectionRepository.class);
    private final GmailProcessedMessageRepository messages = mock(GmailProcessedMessageRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final GmailDrainContinuation continuation = new GmailDrainContinuation(
            jobRepository, connectionRepository, messages, jobService, mapper, mock(PlatformTransactionManager.class));

    private final UUID userId = UUID.randomUUID();
    private final UUID connectionId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private Job job;
    private GmailConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        job = new Job();
        job.setId(jobId);
        job.setUserId(userId);
        job.setType(JobType.GMAIL_SYNC);
        job.setPayload(mapper.writeValueAsString(new GmailSyncPayload(connectionId)));
        job.setResult("{\"processed\":500,\"backlogRemaining\":900}");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        connection = new GmailConnection();
        connection.setId(connectionId);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
        when(messages.countDrainable(eq(connectionId), any(Instant.class))).thenReturn(900L);
    }

    private JobFinishedEvent event(JobType type, JobStatus status) {
        return new JobFinishedEvent(jobId, userId, type, status, JobTrigger.CRON);
    }

    private void verifyNothingEnqueued() {
        verify(jobService, never()).enqueue(any(), any(), any(), any(), any(), any());
    }

    @Test
    void backlogRemainingEnqueuesOneFollowUpWithConnectionDedupKey() {
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
        verify(jobService).enqueue(eq(userId), eq(JobType.GMAIL_SYNC), eq(JobTrigger.CRON),
                eq(new GmailSyncPayload(connectionId)), eq(null), eq(connectionId.toString()));
    }

    @Test
    void noDrainableRowsEnqueuesNothing() {
        when(messages.countDrainable(eq(connectionId), any(Instant.class))).thenReturn(0L);
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
        verifyNothingEnqueued();
    }

    @Test
    void onlyNotYetDueRetryableRowsEnqueuesNothing() {
        // The count applies the nextRetryAt <= now rule, so rows not yet due are not counted.
        when(messages.countDrainable(eq(connectionId), any(Instant.class))).thenReturn(0L);
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
        verify(messages).countDrainable(eq(connectionId), any(Instant.class));
        verifyNothingEnqueued();
    }

    @Test
    void failedJobOrOtherTypeEnqueuesNothing() {
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.FAILED));
        continuation.onJobFinished(event(JobType.STATEMENT_INGEST, JobStatus.SUCCEEDED));
        verifyNothingEnqueued();
    }

    @Test
    void runThatProcessedNothingEnqueuesNothing() {
        job.setResult("{\"processed\":0,\"backlogRemaining\":900}");
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
        verifyNothingEnqueued();
    }

    @Test
    void missingResultEnqueuesNothing() {
        job.setResult(null);
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
        verifyNothingEnqueued();
    }

    @Test
    void disconnectedMailboxEnqueuesNothing() {
        connection.setIsConnected(false);
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
        verifyNothingEnqueued();
    }

    @Test
    void authFailedMailboxEnqueuesNothing() {
        connection.setAuthFailedAt(Instant.now());
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
        verifyNothingEnqueued();
    }

    @Test
    void goneMailboxEnqueuesNothing() {
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.empty());
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
        verifyNothingEnqueued();
    }

    @Test
    void enqueueFailureNeverThrows() {
        when(jobService.enqueue(any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));
        continuation.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));
    }

    @Test
    void activeSyncForTheConnectionIsReusedNotDuplicated() {
        JobRepository repo = mock(JobRepository.class);
        Job running = new Job();
        running.setId(UUID.randomUUID());
        running.setStatus(JobStatus.RUNNING);
        when(repo.findActiveDuplicate(eq(userId), eq(JobType.GMAIL_SYNC), eq(connectionId.toString()), anyCollection()))
                .thenReturn(Optional.of(running));
        JobService real = new JobService(repo, mock(JobArtifactRepository.class), mapper, null, mock(ApplicationEventPublisher.class));
        GmailDrainContinuation c = new GmailDrainContinuation(
                jobRepository, connectionRepository, messages, real, mapper, mock(PlatformTransactionManager.class));

        c.onJobFinished(event(JobType.GMAIL_SYNC, JobStatus.SUCCEEDED));

        verify(repo, never()).save(any());
        assertEquals(JobStatus.RUNNING, running.getStatus());
    }
}
