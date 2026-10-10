package com.financeos.domain.job;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JobsEndpointTest {

    private final JobWorker jobWorker = mock(JobWorker.class);
    private final JobsEndpoint endpoint = new JobsEndpoint(jobWorker);

    @Test
    void status_reportsPausedFlagAndRunningCount() {
        when(jobWorker.isPaused()).thenReturn(true);
        when(jobWorker.getInFlightCount()).thenReturn(2);

        assertThat(endpoint.status()).isEqualTo(Map.of("paused", true, "running", 2));
    }

    @Test
    void setPausedTrue_pausesTheWorker() {
        endpoint.setPaused(true);

        verify(jobWorker).pause();
        verify(jobWorker, never()).resume();
    }

    @Test
    void setPausedFalse_resumesTheWorker() {
        endpoint.setPaused(false);

        verify(jobWorker).resume();
        verify(jobWorker, never()).pause();
    }
}
