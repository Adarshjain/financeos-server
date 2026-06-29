package com.financeos.domain.report.definition;

/**
 * A single column sort instruction. {@code key} matches the column-key convention
 * (field name for raw / group columns; {@code {field}_{aggregation}} for measures).
 */
public record SortClause(
        String key,
        SortDirection direction
) {}
