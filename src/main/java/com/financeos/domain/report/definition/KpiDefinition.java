package com.financeos.domain.report.definition;

import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;

import java.util.List;

/**
 * Definition for a KPI report — a single aggregated scalar value with an optional
 * period-over-period comparison.
 *
 * <p>{@code includeExcluded} is a {@link Boolean} wrapper (nullable) so the validator
 * can detect when the client omitted the field. No default is applied here.
 */
public record KpiDefinition(
        String measure,
        Aggregation aggregation,
        Boolean includeExcluded,
        List<FilterClause> filters,
        Comparison comparison
) implements ReportDefinition {

    @Override
    public ReportType type() {
        return ReportType.KPI;
    }
}
