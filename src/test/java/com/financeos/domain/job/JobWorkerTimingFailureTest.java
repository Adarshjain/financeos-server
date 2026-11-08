package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.observability.ObservabilityMetrics;
import com.financeos.core.observability.Timing;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Broken telemetry must not stop a job from running, finishing, or releasing its slot. */
class JobWorkerTimingFailureTest {

    @AfterEach
    void uninstall() {
        Timing.reset();
    }

    private void installBrokenTelemetry(boolean onStart, boolean onStop) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                if (onStart) throw new IllegalStateException("start boom");
            }

            @Override
            public void onStop(Observation.Context context) {
                if (onStop) throw new IllegalStateException("stop boom");
            }
        });
        Timing.install(registry, null, null, null, 1000L);
    }

    private void runWithBrokenTelemetry(boolean onStart, boolean onStop) throws Exception {
        installBrokenTelemetry(onStart, onStop);
        JobRepository jobRepository = mock(JobRepository.class);
        JobService jobService = mock(JobService.class);
        JobHandlerRegistry registry = mock(JobHandlerRegistry.class);
        JobHandler handler = mock(JobHandler.class);
        when(handler.execute(any())).thenReturn("ok");
        when(registry.get(JobType.PRICE_REFRESH)).thenReturn(handler);
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.PRICE_REFRESH);
        job.setStatus(JobStatus.PENDING);
        job.setCreatedAt(Instant.now().minusSeconds(5));
        when(jobRepository.findByStatusOrderByCreatedAtAsc(eq(JobStatus.PENDING), any())).thenReturn(List.of(job));
        when(jobService.claim(job.getId())).thenReturn(true);
        JobWorker worker = new JobWorker(jobRepository, jobService, registry, mock(ObservabilityMetrics.class),
                Runnable::run, new ObjectMapper(), 2);

        worker.poll();

        verify(handler).execute(any());
        verify(jobService).succeed(eq(job.getId()), any());
        verify(jobService, never()).fail(any(), any(), any());
        assertThat(worker.getInFlightCount()).isEqualTo(0);
    }

    @Test
    void handlerThrowingOnStart_jobStillRunsAndFinishes_andSlotIsReleased() throws Exception {
        runWithBrokenTelemetry(true, false);
    }

    @Test
    void handlerThrowingOnStop_doesNotMaskTheJobResult() throws Exception {
        runWithBrokenTelemetry(false, true);
    }
}
