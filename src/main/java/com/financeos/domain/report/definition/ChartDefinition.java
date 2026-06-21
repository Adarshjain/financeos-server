package com.financeos.domain.report.definition;

import com.financeos.domain.report.ReportType;

import java.util.List;

/**
 * Definition for a Chart report — an aggregated measure across a primary dimension,
 * with an optional series-split dimension.
 *
 * <p>{@code series} is null when no series split is requested. {@code includeExcluded}
 * is a {@link Boolean} wrapper (nullable) so the validator can detect omission.
 */
public record ChartDefinition(
        ChartType chartType,
        DimensionRef dimension,
        DimensionRef series,
        MeasureRef measure,
        Boolean includeExcluded,
        List<FilterClause> filters
) implements ReportDefinition {

    @Override
    public ReportType type() {
        return ReportType.CHART;
    }
}
