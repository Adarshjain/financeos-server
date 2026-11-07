package com.financeos.core.role;

import com.financeos.domain.job.JobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JobQueueMetricsTest {

    private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");

    private double gauge(SimpleMeterRegistry r, String name) {
        return r.get(name).gauge().value();
    }

    @Test
    void reportsCountAndOldestAge() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.pendingStats()).thenReturn(Collections.singletonList(new Object[]{3L, T0.minusSeconds(90)}));
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        new JobQueueMetrics(r, repo, () -> T0);

        assertThat(gauge(r, "financeos.jobs.pending.count")).isEqualTo(3);
        assertThat(gauge(r, "financeos.jobs.pending.oldest.seconds")).isEqualTo(90);
        verify(repo, times(1)).pendingStats();
    }

    @Test
    void emptyQueueIsZero() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.pendingStats()).thenReturn(Collections.singletonList(new Object[]{0L, null}));
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        new JobQueueMetrics(r, repo, () -> T0);

        assertThat(gauge(r, "financeos.jobs.pending.count")).isZero();
        assertThat(gauge(r, "financeos.jobs.pending.oldest.seconds")).isZero();
    }

    @Test
    void cachesThirtySecondsThenRefreshesAndKeepsLastValueOnFailure() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.pendingStats())
                .thenReturn(Collections.singletonList(new Object[]{2L, T0.minusSeconds(10)}))
                .thenThrow(new RuntimeException("db down"))
                .thenReturn(Collections.singletonList(new Object[]{1L, T0.plusSeconds(60).minusSeconds(5)}));
        AtomicReference<Instant> now = new AtomicReference<>(T0);
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        new JobQueueMetrics(r, repo, now::get);

        assertThat(gauge(r, "financeos.jobs.pending.count")).isEqualTo(2);
        now.set(T0.plusSeconds(29));
        assertThat(gauge(r, "financeos.jobs.pending.count")).isEqualTo(2);
        verify(repo, times(1)).pendingStats();

        now.set(T0.plusSeconds(31));
        assertThat(gauge(r, "financeos.jobs.pending.count")).isEqualTo(2);   // refresh threw, last value kept
        verify(repo, times(2)).pendingStats();

        now.set(T0.plusSeconds(62));
        assertThat(gauge(r, "financeos.jobs.pending.count")).isEqualTo(1);
        assertThat(gauge(r, "financeos.jobs.pending.oldest.seconds")).isEqualTo(7);
    }

    @Test
    void noRowsIsZero() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.pendingStats()).thenReturn(List.of());
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        new JobQueueMetrics(r, repo, () -> T0);
        assertThat(gauge(r, "financeos.jobs.pending.count")).isZero();
    }
}
