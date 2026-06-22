package com.financeos.domain.report.definition;

import java.util.List;

/**
 * An aggregated (pivot) table: {@code rows} dimensions form the row groups, {@code columns}
 * dimensions form the column groups (may be empty for a flat row×measure table), and
 * {@code measures} are the aggregated cell values.
 */
public record AggregatedTableDefinition(
        TableMode mode,
        List<DimensionRef> rows,
        List<DimensionRef> columns,
        List<MeasureRef> measures,
        List<FilterClause> filters,
        List<SortClause> sort
) implements TableDefinition {
}
