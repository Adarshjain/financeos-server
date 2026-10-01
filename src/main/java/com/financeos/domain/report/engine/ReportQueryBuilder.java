package com.financeos.domain.report.engine;

import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ReportQueryBuilder {
    /** The base table's primary-key expression: stable row identity and the unique sort tiebreaker. */
    String idExpression();
    String expression(String field, Set<String> joins);
    String fromClause(Set<String> joins);

    /**
     * FROM clause for one query; may bind parameters of its own (e.g. a per-user lookup table).
     * Executors call this one; the default needs no parameters.
     */
    default String fromClause(Set<String> joins, Map<String, Object> params, UUID userId) {
        return fromClause(joins);
    }
    String buildWhere(List<FilterClause> filters, UUID userId, Map<String, Object> params, Set<String> joins);
    String bucketExpression(String dateExpression, Granularity granularity);
}
