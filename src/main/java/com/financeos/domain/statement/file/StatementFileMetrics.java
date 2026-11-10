package com.financeos.domain.statement.file;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * {@code financeos.statement.files} (outcome stored | deduped | skipped, plus reason when skipped) and the
 * {@code .bytes} / {@code .count} gauges. The gauges read the database at most once per 10 minutes and never throw.
 */
@Component
public class StatementFileMetrics {

    private static final Logger log = LoggerFactory.getLogger(StatementFileMetrics.class);
    static final long GAUGE_TTL_NANOS = 10L * 60 * 1_000_000_000L;
    static final long GAUGE_RETRY_NANOS = 60L * 1_000_000_000L;

    private final MeterRegistry registry;
    // Held in fields: Micrometer keeps only a weak reference to a gauge's state object.
    private final CachedLong bytes;
    private final CachedLong count;

    @Autowired
    public StatementFileMetrics(MeterRegistry registry, StatementFileRepository repository) {
        this(registry, repository, System::nanoTime);
    }

    StatementFileMetrics(MeterRegistry registry, StatementFileRepository repository, LongSupplier nanoClock) {
        this.registry = registry;
        this.bytes = new CachedLong(repository::sumSizeBytes, nanoClock, GAUGE_TTL_NANOS, GAUGE_RETRY_NANOS);
        this.count = new CachedLong(repository::count, nanoClock, GAUGE_TTL_NANOS, GAUGE_RETRY_NANOS);
        Gauge.builder("financeos.statement.files.bytes", bytes, CachedLong::get).baseUnit("bytes")
                .strongReference(true).register(registry);
        Gauge.builder("financeos.statement.files.count", count, CachedLong::get)
                .strongReference(true).register(registry);
    }

    public void stored() {
        Counter.builder("financeos.statement.files").tag("outcome", "stored").register(registry).increment();
    }

    public void deduped() {
        Counter.builder("financeos.statement.files").tag("outcome", "deduped").register(registry).increment();
    }

    /** @param reason disabled | empty | too_large | unsupported | user_quota | total_cap | error */
    public void skipped(String reason) {
        Counter.builder("financeos.statement.files").tag("outcome", "skipped").tag("reason", reason)
                .register(registry).increment();
    }

    /**
     * A value read at most once per TTL (retried after a shorter delay when a read fails). NaN until the first
     * successful read. The database call runs with no lock held; callers arriving meanwhile get the last value.
     */
    static final class CachedLong {
        private final LongSupplier source;
        private final LongSupplier nanoClock;
        private final long ttlNanos;
        private final long retryNanos;
        private final AtomicBoolean loading = new AtomicBoolean();
        private volatile double value = Double.NaN;
        private volatile long nextAttemptAt;
        private volatile boolean started;

        CachedLong(LongSupplier source, LongSupplier nanoClock, long ttlNanos, long retryNanos) {
            this.source = source;
            this.nanoClock = nanoClock;
            this.ttlNanos = ttlNanos;
            this.retryNanos = retryNanos;
        }

        double get() {
            long now = nanoClock.getAsLong();
            if ((!started || now - nextAttemptAt >= 0) && loading.compareAndSet(false, true)) {
                try {
                    try {
                        value = source.getAsLong();
                        nextAttemptAt = now + ttlNanos;
                    } catch (Exception e) {
                        log.warn("statement file gauge read failed: {}", e.toString());
                        nextAttemptAt = now + retryNanos;
                    }
                    started = true;
                } finally {
                    loading.set(false);
                }
            }
            return value;
        }
    }
}
