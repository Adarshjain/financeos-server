package com.financeos.domain.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class JobJanitorTest {

    private JobRepository jobRepository;
    private JobArtifactRepository artifactRepository;
    private JobJanitor jobJanitor;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        artifactRepository = mock(JobArtifactRepository.class);
        jobJanitor = new JobJanitor(jobRepository, artifactRepository, 30, 7);
    }

    @Test
    void markInterruptedOnStartup_updatesRunningJobsToFailedInterrupted() {
        jobJanitor.markInterruptedOnStartup();

        verify(jobRepository, times(1)).markInterrupted(
                eq(JobStatus.RUNNING),
                eq(JobStatus.FAILED),
                eq("INTERRUPTED"),
                eq("Interrupted by server restart"),
                any(Instant.class)
        );
    }

    @Test
    void runRetentionPurge_purgesFailedJobArtifactsAndTerminalJobs() {
        jobJanitor.runRetentionPurge();

        verify(jobRepository, times(1)).deleteFailedJobArtifactsOlderThan(any(Instant.class));
        verify(jobRepository, times(1)).deleteTerminalJobsOlderThan(any(), any(Instant.class));
    }
}
