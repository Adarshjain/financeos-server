package com.financeos.core.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import net.logstash.logback.argument.StructuredArguments;
import net.ttddyy.observation.tracing.QueryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Micrometer ObservationHandler intercepting jdbc.query observations and logging db.slow_query at WARN level
 * when execution time >= threshold (default 500 ms).
 */
@Component
public class SlowQueryObservationHandler implements ObservationHandler<Observation.Context> {

    private static final Logger log = LoggerFactory.getLogger("com.financeos.core.observability.Database");

    private final long slowQueryThresholdMs;

    public SlowQueryObservationHandler(
            @Value("${management.metrics.db.slow-query-threshold-ms:500}") long slowQueryThresholdMs) {
        this.slowQueryThresholdMs = slowQueryThresholdMs;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof QueryContext || (context != null && "jdbc.query".equals(context.getName()));
    }

    @Override
    public void onStart(Observation.Context context) {
        context.put("startTimeMs", System.currentTimeMillis());
    }

    @Override
    public void onStop(Observation.Context context) {
        Long startTimeMs = context.getOrDefault("startTimeMs", 0L);
        long durationMs = (startTimeMs != null && startTimeMs > 0) ? System.currentTimeMillis() - startTimeMs : 0;
        if (durationMs >= slowQueryThresholdMs) {
            String sql = null;
            if (context instanceof QueryContext qc) {
                List<String> queries = qc.getQueries();
                if (queries != null && !queries.isEmpty()) {
                    sql = String.join("; ", queries);
                }
            }
            if (sql == null || sql.isBlank()) {
                sql = context.getHighCardinalityKeyValues().stream()
                        .filter(kv -> "jdbc.query".equals(kv.getKey()) || "db.statement".equals(kv.getKey()) || "query".equals(kv.getKey()) || "sql".equals(kv.getKey()))
                        .map(io.micrometer.common.KeyValue::getValue)
                        .findFirst()
                        .orElse(null);
            }
            if (sql != null && ("query".equalsIgnoreCase(sql) || "unknown-sql".equalsIgnoreCase(sql) || sql.isBlank())) {
                sql = null;
            }
            if (sql != null && sql.length() > 500) {
                sql = sql.substring(0, 500);
            }

            if (sql != null) {
                log.warn("Slow SQL query detected ({}ms): sql={}", durationMs, sql,
                        StructuredArguments.keyValue("event", Events.DB_SLOW_QUERY),
                        StructuredArguments.keyValue("elapsedTimeMs", durationMs),
                        StructuredArguments.keyValue("sql", sql));
            } else {
                log.warn("Slow SQL query detected ({}ms)", durationMs,
                        StructuredArguments.keyValue("event", Events.DB_SLOW_QUERY),
                        StructuredArguments.keyValue("elapsedTimeMs", durationMs));
            }
        }
    }
}
