package com.financeos.domain.report.definition;

import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;

import java.util.List;

/**
 * Definition for a KPI report — a single aggregated scalar value with an optional
 * period-over-period comparison. Exclusion of "excluded" transactions, if desired, is expressed
 * as a normal filter on the {@code isExcluded} field (no dedicated flag).
 */
public record KpiDefinition(
        String measure,
        Aggregation aggregation,
        List<FilterClause> filters,
        Comparison comparison
) implements ReportDefinition {

    @Override
    public ReportType type() {
        return ReportType.KPI;
    }
}
