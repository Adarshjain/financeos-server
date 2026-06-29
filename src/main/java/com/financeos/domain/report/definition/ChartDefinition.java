package com.financeos.domain.report.definition;

import com.financeos.domain.report.ReportType;

import java.util.List;

/**
 * Definition for a Chart report — an aggregated measure across a primary dimension, with an
 * optional series-split dimension ({@code series} is null when no split is requested).
 */
public record ChartDefinition(
        ChartType chartType,
        DimensionRef dimension,
        DimensionRef series,
        MeasureRef measure,
        List<FilterClause> filters
) implements ReportDefinition {

    @Override
    public ReportType type() {
        return ReportType.CHART;
    }
}
