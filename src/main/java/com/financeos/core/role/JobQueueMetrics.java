package com.financeos.core.role;

import com.financeos.domain.job.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/** Queue-depth gauges, registered on every role. One query, cached; a failed refresh keeps the last value. */
@Component
public class JobQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(JobQueueMetrics.class);
    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final JobRepository jobRepository;
    private final Supplier<Instant> clock;
    private long pendingCount;
    private double oldestSeconds;
    private Instant loadedAt;

    @Autowired
    public JobQueueMetrics(MeterRegistry registry, JobRepository jobRepository) {
        this(registry, jobRepository, Instant::now);
    }

    JobQueueMetrics(MeterRegistry registry, JobRepository jobRepository, Supplier<Instant> clock) {
        this.jobRepository = jobRepository;
        this.clock = clock;
        Gauge.builder("financeos.jobs.pending.count", this, JobQueueMetrics::count).register(registry);
        Gauge.builder("financeos.jobs.pending.oldest.seconds", this, JobQueueMetrics::oldest).register(registry);
    }

    double count() {
        refresh();
        return pendingCount;
    }

    double oldest() {
        refresh();
        return oldestSeconds;
    }

    private synchronized void refresh() {
        Instant now = clock.get();
        if (loadedAt != null && Duration.between(loadedAt, now).compareTo(CACHE_TTL) < 0) {
            return;
        }
        loadedAt = now;
        try {
            List<Object[]> rows = jobRepository.pendingStats();
            Object[] row = rows.isEmpty() ? null : rows.get(0);
            long count = row == null || row[0] == null ? 0 : ((Number) row[0]).longValue();
            Instant oldest = row == null ? null : (Instant) row[1];
            pendingCount = count;
            oldestSeconds = count == 0 || oldest == null ? 0 : Math.max(0, Duration.between(oldest, now).toMillis() / 1000.0);
        } catch (Exception e) {
            log.warn("Job queue gauge refresh failed; keeping last value", e);
        }
    }
}
