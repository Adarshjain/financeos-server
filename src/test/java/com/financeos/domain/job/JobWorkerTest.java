package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.security.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobWorkerTest {

    private JobRepository jobRepository;
    private JobService jobService;
    private JobHandlerRegistry handlerRegistry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        jobService = mock(JobService.class);
        handlerRegistry = mock(JobHandlerRegistry.class);
        objectMapper = new ObjectMapper();
    }

    @Test
    void poll_handlesRejectedExecution_andFailsJobWithExecutorRejected() {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.PRICE_REFRESH);
        job.setStatus(JobStatus.PENDING);

        when(jobRepository.findByStatusOrderByCreatedAtAsc(eq(JobStatus.PENDING), any()))
                .thenReturn(List.of(job));
        when(jobService.claim(job.getId())).thenReturn(true);

        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("Queue full");
        };

        JobWorker worker = new JobWorker(
                jobRepository,
                jobService,
                handlerRegistry,
                mock(com.financeos.core.observability.ObservabilityMetrics.class),
                rejectingExecutor,
                objectMapper,
                1
        );

        worker.poll();

        verify(jobService, times(1)).fail(job.getId(), "EXECUTOR_REJECTED", "Worker queue full; job not executed");
        assertThat(worker.getInFlightCount()).isEqualTo(0);
    }

    @Test
    void runJob_cleansUpUserContextAndMdc_evenWhenHandlerThrowsException() throws Exception {
        UUID jobIdUserA = UUID.randomUUID();
        UUID userIdUserA = UUID.randomUUID();

        UUID jobIdUserB = UUID.randomUUID();
        UUID userIdUserB = UUID.randomUUID();

        JobHandler successHandler = mock(JobHandler.class);
        when(successHandler.execute(any())).thenReturn("ok");

        JobHandler throwingHandler = mock(JobHandler.class);
        when(throwingHandler.execute(any())).thenThrow(new RuntimeException("Simulated Failure"));

        when(handlerRegistry.get(JobType.PRICE_REFRESH)).thenReturn(successHandler);
        when(handlerRegistry.get(JobType.GMAIL_SYNC)).thenReturn(throwingHandler);

        Executor sameThreadExecutor = Runnable::run;

        JobWorker worker = new JobWorker(
                jobRepository,
                jobService,
                handlerRegistry,
                mock(com.financeos.core.observability.ObservabilityMetrics.class),
                sameThreadExecutor,
                objectMapper,
                2
        );

        // Run Job 1 for User A
        Job jobA = new Job();
        jobA.setId(jobIdUserA);
        jobA.setUserId(userIdUserA);
        jobA.setType(JobType.PRICE_REFRESH);

        when(jobRepository.findByStatusOrderByCreatedAtAsc(eq(JobStatus.PENDING), any()))
                .thenReturn(List.of(jobA));
        when(jobService.claim(jobIdUserA)).thenReturn(true);

        worker.poll();

        // Hygiene check after User A
        assertThat(UserContext.getCurrentUserId()).isNull();
        assertThat(MDC.get("userId")).isNull();

        // Run Job 2 for User B (throwing exception)
        Job jobB = new Job();
        jobB.setId(jobIdUserB);
        jobB.setUserId(userIdUserB);
        jobB.setType(JobType.GMAIL_SYNC);

        when(jobRepository.findByStatusOrderByCreatedAtAsc(eq(JobStatus.PENDING), any()))
                .thenReturn(List.of(jobB));
        when(jobService.claim(jobIdUserB)).thenReturn(true);

        worker.poll();

        // Hygiene check after User B throwing error
        assertThat(UserContext.getCurrentUserId()).isNull();
        assertThat(MDC.get("userId")).isNull();

        verify(jobService, times(1)).fail(eq(jobIdUserB), eq("RuntimeException"), contains("Simulated Failure"));
    }
}
