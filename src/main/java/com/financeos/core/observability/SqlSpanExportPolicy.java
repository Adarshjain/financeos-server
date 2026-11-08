package com.financeos.core.observability;

import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Kill switch {@code financeos.timing.sql-spans} (env TIMING_SQL_SPANS, default true). When false the JDBC spans
 * (datasource-micrometer's "connection", "query" and "result-set") are dropped at export, so traces stay small
 * while the jdbc.* observations keep feeding the slow-SQL log, the query counter and the metrics.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "financeos.timing.sql-spans", havingValue = "false")
public class SqlSpanExportPolicy {

    static final Set<String> JDBC_SPAN_NAMES = Set.of("connection", "query", "result-set");

    @Bean
    SpanExportingPredicate dropJdbcSpans() {
        return span -> !JDBC_SPAN_NAMES.contains(span.getName());
    }
}
