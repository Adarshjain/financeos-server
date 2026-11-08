package com.financeos.core.observability;

import com.financeos.core.role.AppRole;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.HdrHistogram.IntCountsHistogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Keeps an in-memory latency histogram per op (every "financeos.op" observation and every http server request) and
 * flushes it hourly into {@code perf_rollup}, so a regression stays comparable across deploys long after the live
 * metrics expired. Global: ops, role and build version only, never a user.
 *
 * <p>It runs on its own single thread, not {@code @Scheduled} (scheduling is off on the api role, and both roles must
 * flush). It also flushes on shutdown, which every deploy restart triggers. Only the worker (or all) role purges rows
 * older than a year. Telemetry never breaks work: record and flush swallow everything and log at WARN on flush failure.
 */
@Component
@ConditionalOnProperty(name = "financeos.timing.rollup.enabled", havingValue = "true", matchIfMissing = true)
public class PerfRollupAggregator implements ObservationHandler<Observation.Context>, SmartLifecycle {

    static final int MAX_OPS = 1000;
    static final String OVERFLOW_OP = "overflow";
    static final int RETENTION_DAYS = 365;
    static final int MAX_OP_LENGTH = 200;
    static final long SHUTDOWN_DEADLINE_MS = 5000;
    private static final java.util.Set<String> HTTP_METHODS =
            java.util.Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private static final Logger log = LoggerFactory.getLogger(PerfRollupAggregator.class);
    private static final String START_KEY = "financeos.rollup.start";
    /** Durations are kept in microseconds; the histogram tops out at one hour (longer values are clamped). */
    private static final long MAX_TRACKABLE_MICROS = TimeUnit.HOURS.toMicros(1);

    private static final String MERGE_SQL = """
            MERGE INTO perf_rollup t
            USING (SELECT CAST(? AS VARCHAR2(200)) op, CAST(? AS VARCHAR2(20)) role, CAST(? AS VARCHAR2(40)) version,
                          CAST(? AS TIMESTAMP) hour_start FROM dual) s
            ON (t.op = s.op AND t.role = s.role AND t.version = s.version AND t.hour_start = s.hour_start)
            WHEN MATCHED THEN UPDATE SET
                t.p50_ms = ROUND((t.p50_ms * t.count + ? * ?) / (t.count + ?)),
                t.p95_ms = ROUND((t.p95_ms * t.count + ? * ?) / (t.count + ?)),
                t.p99_ms = ROUND((t.p99_ms * t.count + ? * ?) / (t.count + ?)),
                t.max_ms = GREATEST(t.max_ms, ?),
                t.total_ms = t.total_ms + ?,
                t.error_count = t.error_count + ?,
                t.count = t.count + ?
            WHEN NOT MATCHED THEN INSERT (id, op, role, version, hour_start, count, total_ms, p50_ms, p95_ms, p99_ms,
                                          max_ms, error_count)
                VALUES (?, s.op, s.role, s.version, s.hour_start, ?, ?, ?, ?, ?, ?, ?)
            """;

    // resolved at flush time: this handler is built with the ObservationRegistry, which Flyway/DataSource setup
    // depends on, so injecting JdbcTemplate directly would be a bean cycle
    private final Supplier<JdbcTemplate> jdbcSupplier;
    private final String role;
    private final String version;
    private final boolean purges;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Object flushLock = new Object();
    private volatile Instant windowStart = Instant.now();
    private ScheduledExecutorService executor;

    @Autowired
    public PerfRollupAggregator(ObjectProvider<JdbcTemplate> jdbc,
                                @Value("${app.role:all}") String role,
                                @Value("${app.version:unknown}") String version) {
        this(jdbc::getObject, role, version);
    }

    PerfRollupAggregator(JdbcTemplate jdbc, String role, String version) {
        this((Supplier<JdbcTemplate>) () -> jdbc, role, version);
    }

    private PerfRollupAggregator(Supplier<JdbcTemplate> jdbc, String role, String version) {
        this.jdbcSupplier = jdbc;
        AppRole parsed = AppRole.parse(role);
        this.role = parsed.name().toLowerCase(java.util.Locale.ROOT);
        this.version = cap(version == null || version.isBlank() ? "unknown" : version, 40);
        this.purges = parsed != AppRole.API;
    }

    private volatile boolean running;

    /** Starts the flush thread: hourly on the hour, and a daily purge on the worker (or all) role. */
    @PostConstruct
    void startExecutor() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "perf-rollup");
            t.setDaemon(true);
            return t;
        });
        long toNextHour = Duration.between(Instant.now(), Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS))
                .toMillis();
        executor.scheduleAtFixedRate(() -> flush(Instant.now()), toNextHour + 1000, TimeUnit.HOURS.toMillis(1),
                TimeUnit.MILLISECONDS);
        if (purges) {
            executor.scheduleAtFixedRate(() -> purge(Instant.now()), 5, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
        }
    }

    // SmartLifecycle with the lowest phase: lifecycles stop in descending phase order, so this stops last (after web
    // graceful shutdown and the job executors), and every lifecycle stop runs before bean destruction closes the
    // DataSource. The final flush therefore still has its pool.
    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (executor != null) {
            executor.shutdown();
        }
        flush(Instant.now(), SHUTDOWN_DEADLINE_MS);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    /** Fallback if the context never ran lifecycles; idempotent, so the flush never runs twice. */
    @PreDestroy
    void destroy() {
        if (executor != null) {
            executor.shutdown();
        }
        stop();
    }

    // ---- recording -------------------------------------------------------------------------------------------

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context != null
                && (Timing.OBSERVATION_NAME.equals(context.getName()) || context instanceof ServerRequestObservationContext);
    }

    @Override
    public void onStart(Observation.Context context) {
        try {
            context.put(START_KEY, System.nanoTime());
        } catch (RuntimeException e) {
            // telemetry only
        }
    }

    @Override
    public void onStop(Observation.Context context) {
        try {
            Long start = context.get(START_KEY);
            if (start == null) {
                return;
            }
            long micros = (System.nanoTime() - start) / 1000L;
            if (context instanceof ServerRequestObservationContext http) {
                String route = http.getPathPattern();
                String rawMethod = http.getCarrier() != null ? http.getCarrier().getMethod() : null;
                // the method comes from the client: only real verbs become part of an op key
                String method = rawMethod != null && HTTP_METHODS.contains(rawMethod) ? rawMethod : "OTHER";
                int status = http.getResponse() != null ? http.getResponse().getStatus() : 0;
                record("http " + method + " " + (route == null || route.isBlank() ? "UNMATCHED" : route), micros,
                        context.getError() != null || status >= 500);
            } else {
                String op = context.getContextualName();
                if (op == null) {
                    return;
                }
                io.micrometer.common.KeyValue outcome = context.getLowCardinalityKeyValue("outcome");
                record(op, micros, context.getError() != null || (outcome != null && "error".equals(outcome.getValue())));
            }
        } catch (RuntimeException e) {
            // telemetry must never break a request or a job
        }
    }

    void record(String op, long micros, boolean error) {
        try {
            op = cap(op, MAX_OP_LENGTH);
            Bucket b = buckets.get(op);
            if (b == null) {
                // cap the distinct ops so a bug that mints op names cannot grow memory; the rest share one key
                b = buckets.size() >= MAX_OPS && !buckets.containsKey(op)
                        ? buckets.computeIfAbsent(OVERFLOW_OP, k -> new Bucket())
                        : buckets.computeIfAbsent(op, k -> new Bucket());
            }
            b.add(Math.max(0, Math.min(micros, MAX_TRACKABLE_MICROS)), error);
        } catch (RuntimeException e) {
            // telemetry only
        }
    }

    // ---- flushing --------------------------------------------------------------------------------------------

    /** Writes and clears everything recorded since the last flush into the hour the window started in. */
    void flush(Instant now) {
        flush(now, 0);
    }

    /**
     * deadlineMs > 0 is the shutdown path: stop at the first failed upsert or when the deadline passes (a stuck
     * pool would otherwise block once per op), and log one WARN with how many ops were dropped.
     */
    void flush(Instant now, long deadlineMs) {
        long deadline = deadlineMs > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs) : 0;
        synchronized (flushLock) {
            try {
                Instant hour = windowStart.truncatedTo(ChronoUnit.HOURS);
                windowStart = now;
                LocalDateTime hourStart = LocalDateTime.ofInstant(hour, ZoneOffset.UTC);
                int dropped = 0;
                boolean abandon = false;
                for (Map.Entry<String, Bucket> e : buckets.entrySet()) {
                    if (abandon) {
                        dropped++;
                        continue;
                    }
                    Snapshot s = e.getValue().drain();
                    if (s == null) {
                        continue;
                    }
                    if (deadline != 0 && System.nanoTime() - deadline > 0) {
                        dropped++;
                        abandon = true;
                        continue;
                    }
                    try {
                        upsert(e.getKey(), hourStart, s);
                    } catch (RuntimeException ex) {
                        if (deadline != 0) {
                            dropped++;
                            abandon = true;
                            log.warn("Perf rollup shutdown flush failed ({}), dropping the remaining ops", ex.toString());
                        } else {
                            log.warn("Perf rollup flush failed for one op: {}", ex.toString());
                        }
                    }
                }
                if (dropped > 0) {
                    log.warn("Perf rollup shutdown flush dropped {} op(s)", dropped);
                }
            } catch (RuntimeException ex) {
                log.warn("Perf rollup flush failed: {}", ex.toString());
            }
        }
    }

    private void upsert(String op, LocalDateTime hourStart, Snapshot s) {
        Timestamp ts = Timestamp.valueOf(hourStart);
        // On a key conflict (a restart inside the same hour and version) the percentiles are the count-weighted
        // average of the stored and the new window. That is an approximation (percentiles do not average
        // exactly), good enough for regression checks; count, total, errors add and max is exact.
        jdbcSupplier.get().update(MERGE_SQL,
                op, role, version, ts,
                s.p50, s.count, s.count,
                s.p95, s.count, s.count,
                s.p99, s.count, s.count,
                s.max, s.total, s.errors, s.count,
                UUID.randomUUID().toString(), s.count, s.total, s.p50, s.p95, s.p99, s.max, s.errors);
    }

    /** Deletes rollups older than a year. */
    void purge(Instant now) {
        try {
            LocalDateTime cutoff = LocalDateTime.ofInstant(now.minus(RETENTION_DAYS, ChronoUnit.DAYS), ZoneOffset.UTC);
            jdbcSupplier.get().update("DELETE FROM perf_rollup WHERE hour_start < ?", Timestamp.valueOf(cutoff));
        } catch (RuntimeException e) {
            log.warn("Perf rollup purge failed: {}", e.toString());
        }
    }

    int distinctOps() {
        return buckets.size();
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record Snapshot(long count, long total, long p50, long p95, long p99, long max, long errors) {
    }

    private static final class Bucket {
        private final IntCountsHistogram histogram = new IntCountsHistogram(MAX_TRACKABLE_MICROS, 2);
        private long totalMicros;
        private long errors;

        synchronized void add(long micros, boolean error) {
            histogram.recordValue(micros);
            totalMicros += micros;
            if (error) {
                errors++;
            }
        }

        /** The window's numbers in ms, then reset; null when nothing was recorded. */
        synchronized Snapshot drain() {
            long count = histogram.getTotalCount();
            if (count == 0) {
                return null;
            }
            Snapshot s = new Snapshot(count, Math.round(totalMicros / 1000.0),
                    ms(histogram.getValueAtPercentile(50)), ms(histogram.getValueAtPercentile(95)),
                    ms(histogram.getValueAtPercentile(99)), ms(histogram.getMaxValue()), errors);
            histogram.reset();
            totalMicros = 0;
            errors = 0;
            return s;
        }

        private static long ms(long micros) {
            return Math.round(micros / 1000.0);
        }
    }
}
