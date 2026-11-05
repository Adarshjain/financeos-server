package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The failure stack stored by the worker, and the dismissal a retry leaves on the original job. */
class JobFailureDetailTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobArtifactRepository artifactRepository = mock(JobArtifactRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final JobHandlerRegistry registry = mock(JobHandlerRegistry.class);

    private JobWorker runFailingJob(RuntimeException failure, JobService svc) throws Exception {
        JobHandler handler = mock(JobHandler.class);
        when(handler.execute(any())).thenThrow(failure);
        when(registry.get(JobType.PRICE_REFRESH)).thenReturn(handler);
        Executor sameThread = Runnable::run;
        return new JobWorker(jobRepository, svc, registry, mock(com.financeos.core.observability.ObservabilityMetrics.class),
                sameThread, new ObjectMapper(), 1);
    }

    private Job pendingJob() {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setUserId(UUID.randomUUID());
        job.setType(JobType.PRICE_REFRESH);
        when(jobRepository.findByStatusOrderByCreatedAtAsc(eq(JobStatus.PENDING), any())).thenReturn(List.of(job));
        when(jobService.claim(job.getId())).thenReturn(true);
        return job;
    }

    @Test
    void worker_storesTheStackOfAFailedJob() throws Exception {
        Job job = pendingJob();
        runFailingJob(new IllegalStateException("boom", new IllegalArgumentException("root")), jobService).poll();

        verify(jobService).fail(eq(job.getId()), eq("IllegalStateException"), eq("boom"));
        ArgumentCaptor<String> stack = ArgumentCaptor.forClass(String.class);
        verify(jobService).saveErrorDetail(eq(job.getId()), stack.capture());
        assertThat(stack.getValue()).contains("java.lang.IllegalStateException: boom").contains("Caused by: java.lang.IllegalArgumentException: root");
    }

    @Test
    void worker_truncatesAHugeStackTo16000Chars() throws Exception {
        Job job = pendingJob();
        RuntimeException deep = new RuntimeException("x".repeat(50_000));
        runFailingJob(deep, jobService).poll();

        ArgumentCaptor<String> stack = ArgumentCaptor.forClass(String.class);
        verify(jobService).saveErrorDetail(eq(job.getId()), stack.capture());
        assertThat(stack.getValue()).hasSize(16000);
    }

    @Test
    void worker_stillFailsTheJobWhenStoringTheStackFails() throws Exception {
        Job job = pendingJob();
        doThrow(new IllegalStateException("db down")).when(jobService).saveErrorDetail(any(), any());
        runFailingJob(new IllegalStateException("boom"), jobService).poll();

        verify(jobService).fail(eq(job.getId()), eq("IllegalStateException"), eq("boom"));
    }

    @Test
    void saveErrorDetail_writesTheStackOnTheJob() {
        JobService real = new JobService(jobRepository, artifactRepository, new ObjectMapper(), null, null);
        Job job = new Job();
        job.setId(UUID.randomUUID());
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        real.saveErrorDetail(job.getId(), "the stack");

        assertThat(job.getErrorDetail()).isEqualTo("the stack");
        verify(jobRepository).save(job);
    }

    @Test
    void userRetry_dismissesTheOriginalWithTheUsersEmail() {
        JobService real = new JobService(jobRepository, artifactRepository, new ObjectMapper(), null, null);
        UUID userId = UUID.randomUUID();
        Job orig = failedJob(userId);
        when(jobRepository.findByIdAndUserId(orig.getId(), userId)).thenReturn(Optional.of(orig));
        when(jobRepository.findUserEmail(userId)).thenReturn("me@example.test");
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(UUID.randomUUID());
            }
            return j;
        });
        when(artifactRepository.findByJobId(orig.getId())).thenReturn(Collections.emptyList());

        Job fresh = real.retry(userId, orig.getId());

        assertThat(orig.getDismissedAt()).isNotNull();
        assertThat(orig.getDismissedBy()).isEqualTo("me@example.test");
        assertThat(orig.getDismissReason()).isEqualTo("Retried as " + fresh.getId());
    }

    @Test
    void adminRetry_cloneAnyUsersJobAndDismissesTheOriginalWithTheAdminsEmail() {
        JobService real = new JobService(jobRepository, artifactRepository, new ObjectMapper(), null, null);
        Job orig = failedJob(UUID.randomUUID());
        when(jobRepository.findById(orig.getId())).thenReturn(Optional.of(orig));
        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(UUID.randomUUID());
            }
            return j;
        });
        when(artifactRepository.findByJobId(orig.getId())).thenReturn(Collections.emptyList());

        Job fresh = real.retryForAdmin(orig.getId(), "admin@example.test");

        assertThat(fresh.getUserId()).isEqualTo(orig.getUserId());
        assertThat(fresh.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(orig.getDismissedBy()).isEqualTo("admin@example.test");
        assertThat(orig.getDismissReason()).isEqualTo("Retried as " + fresh.getId());
    }

    @Test
    void failedRetryLeavesTheOriginalUndismissed() {
        JobService real = new JobService(jobRepository, artifactRepository, new ObjectMapper(), null, null);
        Job orig = failedJob(UUID.randomUUID());
        orig.setType(JobType.STATEMENT_INGEST);
        when(jobRepository.findById(orig.getId())).thenReturn(Optional.of(orig));
        when(artifactRepository.findByJobId(orig.getId())).thenReturn(Collections.emptyList());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> real.retryForAdmin(orig.getId(), "admin@example.test"))
                .isInstanceOf(com.financeos.core.exception.ValidationException.class);
        assertThat(orig.getDismissedAt()).isNull();
    }

    private static Job failedJob(UUID userId) {
        Job j = new Job();
        j.setId(UUID.randomUUID());
        j.setUserId(userId);
        j.setType(JobType.PRICE_REFRESH);
        j.setStatus(JobStatus.FAILED);
        return j;
    }
}
