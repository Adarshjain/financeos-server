package com.financeos.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The one way code times a step. Each op is one Observation named {@value #OBSERVATION_NAME}: the Timer
 * (op, outcome tags), a span named by the op, and attributes only from {@link Attr}.
 *
 * <p>A static facade so static code can use it; {@link TimingConfig} installs the Spring registry at startup.
 * Before that, and in plain unit tests, it is a no-op that still runs the code.
 */
public final class Timing {

    public static final String OBSERVATION_NAME = "financeos.op";
    public static final String JOB_WAIT_METER = "financeos.job.wait";

    /** Op names are code constants; the pattern keeps the metric tag set small and queryable. */
    public static final Pattern OP_PATTERN = Pattern.compile("^(job|stage|ext|db|http)\\.[a-z0-9_]+(\\.[a-z0-9_]+)*$");

    private static final Logger slowLog = LoggerFactory.getLogger(Timing.class);
    private static final Set<String> VALID_OPS = ConcurrentHashMap.newKeySet();

    private static volatile Backend backend = Backend.NONE;

    private Timing() {
    }

    /** The allow-list of high-cardinality attributes; an enum so a stray key does not compile. */
    public enum Attr {
        USER_ID("user.id"),
        ROWS("rows"),
        MAILS("mails"),
        ITEMS("items"),
        COUNT("count"),
        BYTES("bytes"),
        STATUS("status"),
        ERROR_CODE("error.code"),
        ERROR_TYPE("error.type"),
        RETRIES("retries"),
        CACHE_HIT("cache.hit"),
        METHOD("method"),
        PAGE("page"),
        JOB_ID("job.id"),
        JOB_TYPE("job.type"),
        DB_QUERIES("db.queries");

        private final String key;

        Attr(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Exception> {
        T get() throws E;
    }

    @FunctionalInterface
    public interface ThrowingRunnable<E extends Exception> {
        void run() throws E;
    }

    record Backend(ObservationRegistry observations, MeterRegistry meters, Tracer tracer, Propagator propagator,
                   long slowLogMs) {
        static final Backend NONE = new Backend(ObservationRegistry.NOOP, null, null, null, 1000L);
    }

    /** Called once at startup (and by tests). Null registries fall back to the no-op. */
    public static void install(ObservationRegistry observations, MeterRegistry meters, Tracer tracer,
                               Propagator propagator, long slowLogMs) {
        backend = new Backend(observations != null ? observations : ObservationRegistry.NOOP, meters, tracer,
                propagator, slowLogMs);
    }

    /** Back to the no-op; for tests. */
    public static void reset() {
        backend = Backend.NONE;
    }

    public static <T, E extends Exception> T call(String op, ThrowingSupplier<T, E> body) throws E {
        try (Span span = start(op)) {
            try {
                return body.get();
            } catch (Throwable t) {
                span.error(t);
                throw t;
            }
        }
    }

    /**
     * Like {@link #call(String, ThrowingSupplier)}, but an exception the call site expects (a wrong password, a
     * message that is gone) records outcome=rejected instead of error, so error rates carry real failures only.
     * It is rethrown unchanged either way.
     */
    public static <T, E extends Exception> T call(String op, java.util.function.Predicate<Throwable> expected,
                                                  ThrowingSupplier<T, E> body) throws E {
        try (Span span = start(op)) {
            try {
                return body.get();
            } catch (Throwable t) {
                if (expected != null && expected.test(t)) {
                    span.rejected(t);
                } else {
                    span.error(t);
                }
                throw t;
            }
        }
    }

    public static <E extends Exception> void run(String op, ThrowingRunnable<E> body) throws E {
        call(op, () -> {
            body.run();
            return null;
        });
    }

    public static Span start(String op) {
        return start(op, null);
    }

    /**
     * Starts an op as a child of a stored W3C traceparent (a job continuing the request that enqueued it).
     * Without a tracer, or when the header cannot be read, it is a plain root op.
     */
    public static Span start(String op, String traceParent) {
        Backend b = backend;
        try {
            validate(op);
        } catch (IllegalArgumentException e) {
            // a bad op name must not break the work it times: run it untimed
            slowLog.debug("Timing ignored invalid op: {}", e.getMessage());
            return new Span(String.valueOf(op), null, null, b.slowLogMs());
        }
        io.micrometer.tracing.Span remote = null;
        Tracer.SpanInScope remoteScope = null;
        Observation obs = null;
        try {
            if (traceParent != null && !traceParent.isBlank() && b.tracer() != null && b.propagator() != null) {
                try {
                    remote = b.propagator().extract(Map.of("traceparent", traceParent), Map<String, String>::get)
                            .name("job.queued").start();
                    remoteScope = b.tracer().withSpan(remote);
                } catch (RuntimeException e) {
                    remote = null;
                    remoteScope = null;
                }
            }
            try {
                obs = Observation.createNotStarted(OBSERVATION_NAME, Observation.Context::new, b.observations())
                        .contextualName(op)
                        .lowCardinalityKeyValue("op", op)
                        .start();
            } finally {
                // the span's parent is fixed at start, so the remote hop is only needed until now; it is closed before
                // the op's own scope opens so the scopes unwind in order
                closeRemote(remoteScope, remote);
                remoteScope = null;
                remote = null;
            }
            return new Span(op, obs, obs.openScope(), b.slowLogMs());
        } catch (RuntimeException e) {
            // telemetry must never break the work it measures: run it untimed
            slowLog.debug("Timing could not start op {}: {}", op, e.toString());
            closeRemote(remoteScope, remote);
            if (obs != null) {
                try {
                    obs.stop();
                } catch (RuntimeException ignored) {
                    // already failing
                }
            }
            return new Span(op, null, null, b.slowLogMs());
        }
    }

    private static void closeRemote(Tracer.SpanInScope scope, io.micrometer.tracing.Span span) {
        try {
            if (scope != null) {
                scope.close();
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
        try {
            if (span != null) {
                span.end();
            }
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    /** The current span's W3C traceparent, or null when there is no active span (tracing off, or none open). */
    public static String currentTraceParent() {
        Backend b = backend;
        if (b.tracer() == null || b.propagator() == null) {
            return null;
        }
        try {
            io.micrometer.tracing.Span current = b.tracer().currentSpan();
            if (current == null) {
                return null;
            }
            Map<String, String> carrier = new HashMap<>();
            b.propagator().inject(current.context(), carrier, Map::put);
            return carrier.get("traceparent");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Timer "financeos.job.wait" (tag type): how long a job sat PENDING before a worker claimed it. */
    public static void recordJobWait(String type, Duration wait) {
        try {
            MeterRegistry meters = backend.meters();
            if (meters == null || wait == null) {
                return;
            }
            Timer.builder(JOB_WAIT_METER).tag("type", type == null ? "unknown" : type.toLowerCase(Locale.ROOT))
                    .register(meters).record(wait.isNegative() ? Duration.ZERO : wait);
        } catch (RuntimeException e) {
            slowLog.debug("Timing could not record job wait: {}", e.toString());
        }
    }

    /** Turns free text (a provider id, a source name) into a valid op segment. */
    public static String segment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    }

    static void validate(String op) {
        if (op == null) {
            throw new IllegalArgumentException("Timing op is null");
        }
        if (VALID_OPS.contains(op)) {
            return;
        }
        if (!OP_PATTERN.matcher(op).matches()) {
            throw new IllegalArgumentException("Invalid timing op '" + op + "', expected " + OP_PATTERN.pattern());
        }
        VALID_OPS.add(op);
    }

    /** What a span reports as its error: the exception class name only, no message, no stack. */
    private static final class SpanError extends RuntimeException {
        SpanError(String type) {
            super(type, null, false, false);
        }
    }

    /** One timed step; close() ends it. Safe to close twice. */
    public static final class Span implements AutoCloseable {
        private final String op;
        private final Observation observation;
        private final Observation.Scope scope;
        private final long slowLogMs;
        private final long startNanos = System.nanoTime();
        private final Map<Attr, Object> attrs = new EnumMap<>(Attr.class);
        private Throwable error;
        private boolean rejected;
        private boolean closed;

        private Span(String op, Observation observation, Observation.Scope scope, long slowLogMs) {
            this.op = op;
            this.observation = observation;
            this.scope = scope;
            this.slowLogMs = slowLogMs;
        }

        public Span attr(Attr key, Object value) {
            if (value != null && !closed) {
                attrs.put(key, value);
                if (observation != null) {
                    try {
                        observation.highCardinalityKeyValue(key.key(), String.valueOf(value));
                    } catch (RuntimeException e) {
                        slowLog.debug("Timing could not set attribute {}: {}", key.key(), e.toString());
                    }
                }
            }
            return this;
        }

        public Span error(Throwable t) {
            if (t != null && !closed) {
                this.error = t;
                // only the class name leaves the process: messages and stacks can carry emails or prompt text
                markError(t.getClass().getSimpleName());
            }
            return this;
        }

        /** An expected failure: outcome=rejected (not an error), with the class name as error.type; no span error. */
        public Span rejected(Throwable t) {
            if (t != null && !closed) {
                this.rejected = true;
                attr(Attr.ERROR_TYPE, t.getClass().getSimpleName());
            }
            return this;
        }

        /** Marks the op failed without an exception (an HTTP status of 400 or more): outcome=error, error.type attr. */
        public Span errorType(String type) {
            if (type != null && !closed) {
                this.error = new SpanError(type);
                markError(type);
            }
            return this;
        }

        private void markError(String type) {
            attr(Attr.ERROR_TYPE, type);
            if (observation != null) {
                try {
                    observation.error(new SpanError(type));
                } catch (RuntimeException e) {
                    slowLog.debug("Timing could not mark error: {}", e.toString());
                }
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            if (observation != null) {
                try {
                    observation.lowCardinalityKeyValue("outcome", error != null ? "error" : rejected ? "rejected" : "ok");
                } catch (RuntimeException e) {
                    slowLog.debug("Timing could not set outcome: {}", e.toString());
                }
                try {
                    scope.close();
                } catch (RuntimeException e) {
                    slowLog.debug("Timing could not close scope: {}", e.toString());
                }
                try {
                    observation.stop();
                } catch (RuntimeException e) {
                    slowLog.debug("Timing could not stop op: {}", e.toString());
                }
            }
            if (durationMs >= slowLogMs) {
                logSlow(durationMs);
            }
        }

        private void logSlow(long durationMs) {
            try {
                logSlowLine(durationMs);
            } catch (RuntimeException e) {
                slowLog.debug("Timing could not log slow op: {}", e.toString());
            }
        }

        private void logSlowLine(long durationMs) {
            Map<String, Object> fields = new LinkedHashMap<>();
            attrs.forEach((k, v) -> fields.put("attr." + k.key(), v));
            slowLog.info("Slow op",
                    StructuredArguments.keyValue("event", "op.slow"),
                    StructuredArguments.keyValue("op", op),
                    StructuredArguments.keyValue("durationMs", durationMs),
                    StructuredArguments.entries(fields));
        }
    }
}
