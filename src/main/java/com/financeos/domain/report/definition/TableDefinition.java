package com.financeos.domain.report.definition;

import com.financeos.domain.report.ReportType;

import java.util.List;

/**
 * Definition for a Table report. Supports two modes:
 * <ul>
 *   <li>{@link TableMode#RAW} — selected columns from the transaction list.</li>
 *   <li>{@link TableMode#AGGREGATED} — GROUP BY summary with one or more measures.</li>
 * </ul>
 *
 * <p>Fields that are only relevant to one mode ({@code columns} for RAW;
 * {@code groupBy} and {@code measures} for AGGREGATED) may be null or empty in the
 * other mode; the validator enforces the cross-mode constraints. {@code includeExcluded}
 * is a {@link Boolean} wrapper (nullable) so omission is detectable.
 */
public record TableDefinition(
        TableMode mode,
        List<String> columns,
        List<DimensionRef> groupBy,
        List<MeasureRef> measures,
        Boolean includeExcluded,
        List<FilterClause> filters,
        List<SortClause> sort,
        Integer pageSize
) implements ReportDefinition {

    @Override
    public ReportType type() {
        return ReportType.TABLE;
    }
}
