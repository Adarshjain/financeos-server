package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class JobServiceTest {

    private JobRepository jobRepository;
    private JobArtifactRepository jobArtifactRepository;
    private ObjectMapper objectMapper;
    private JobService jobService;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobArtifactRepository = mock(JobArtifactRepository.class);
        objectMapper = new ObjectMapper();
        jobService = new JobService(jobRepository, jobArtifactRepository, objectMapper, null);
    }

    @Test
    void enqueue_createsNewJob_whenNoActiveDuplicate() {
        UUID userId = UUID.randomUUID();
        when(jobRepository.findActiveDuplicate(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        Job job = jobService.enqueue(userId, JobType.PRICE_REFRESH, JobTrigger.USER, "payload", null, "key1");

        assertThat(job).isNotNull();
        assertThat(job.getType()).isEqualTo(JobType.PRICE_REFRESH);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        verify(jobRepository, times(1)).save(any(Job.class));
    }

    @Test
    void enqueue_returnsExistingActiveJob_whenDuplicateExists() {
        UUID userId = UUID.randomUUID();
        Job existing = new Job();
        existing.setUserId(userId);
        existing.setType(JobType.GMAIL_SYNC);
        existing.setStatus(JobStatus.RUNNING);
        existing.setDedupKey("key1");

        when(jobRepository.findActiveDuplicate(eq(userId), eq(JobType.GMAIL_SYNC), eq("key1"), any()))
                .thenReturn(Optional.of(existing));

        Job job = jobService.enqueue(userId, JobType.GMAIL_SYNC, JobTrigger.USER, "payload", null, "key1");

        assertThat(job).isEqualTo(existing);
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void enqueue_skipsDedup_whenDedupKeyIsNull() {
        UUID userId = UUID.randomUUID();
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        Job job = jobService.enqueue(userId, JobType.PRICE_REFRESH, JobTrigger.USER, "payload", null, null);

        assertThat(job).isNotNull();
        verify(jobRepository, never()).findActiveDuplicate(any(), any(), any(), any());
    }

    @Test
    void requestCancel_throwsResourceNotFound_whenUserIdIsNull() {
        UUID jobId = UUID.randomUUID();

        assertThatThrownBy(() -> jobService.requestCancel(null, jobId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requestCancel_throwsResourceNotFound_whenForeignUserId() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.requestCancel(userId, jobId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requestCancel_cancelsPendingJobAndDeletesArtifacts() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Job job = new Job();
        job.setId(jobId);
        job.setUserId(userId);
        job.setStatus(JobStatus.PENDING);

        when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.of(job));
        when(jobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Job cancelled = jobService.requestCancel(userId, jobId);
        assertThat(cancelled.getStatus()).isEqualTo(JobStatus.CANCELLED);
        verify(jobArtifactRepository, times(1)).deleteByJobId(jobId);
    }

    @Test
    void retry_throwsResourceNotFound_whenUserIdIsNull() {
        UUID jobId = UUID.randomUUID();

        assertThatThrownBy(() -> jobService.retry(null, jobId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void retry_throwsResourceNotFound_whenForeignUserId() {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        when(jobRepository.findByIdAndUserId(jobId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.retry(userId, jobId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void retry_clonesJobPayloadAndArtifacts_andNullsDedupKey() {
        UUID userId = UUID.randomUUID();
        UUID origId = UUID.randomUUID();
        Job orig = new Job();
        orig.setId(origId);
        orig.setUserId(userId);
        orig.setType(JobType.PRICE_REFRESH);
        orig.setStatus(JobStatus.FAILED);
        orig.setPayload("{\"test\":true}");
        orig.setDedupKey("manual-key");

        when(jobRepository.findByIdAndUserId(origId, userId)).thenReturn(Optional.of(orig));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobArtifactRepository.findByJobId(origId)).thenReturn(Collections.emptyList());

        Job retried = jobService.retry(userId, origId);

        assertThat(retried.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(retried.getPayload()).isEqualTo("{\"test\":true}");
        assertThat(retried.getDedupKey()).isNull();
    }

    @Test
    void retry_throwsValidationException_whenStatementIngestArtifactsPurged() {
        UUID userId = UUID.randomUUID();
        UUID origId = UUID.randomUUID();
        Job orig = new Job();
        orig.setId(origId);
        orig.setUserId(userId);
        orig.setType(JobType.STATEMENT_INGEST);
        orig.setStatus(JobStatus.FAILED);

        when(jobRepository.findByIdAndUserId(origId, userId)).thenReturn(Optional.of(orig));
        when(jobArtifactRepository.findByJobId(origId)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> jobService.retry(userId, origId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Statement ingest artifacts expired");
    }
}
