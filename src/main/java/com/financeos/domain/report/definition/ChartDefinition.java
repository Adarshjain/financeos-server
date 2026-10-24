package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.financeos.domain.report.ReportType;

import java.util.List;

/**
 * Definition for a Chart report — an aggregated measure across a primary dimension, with an
 * optional series-split dimension ({@code series} is null when no split is requested).
 *
 * <p>Optional shaping (absent = the plain chart): {@code limit} keeps the top N categories by
 * absolute value, {@code other} folds the rest into a last "Other" category, {@code order} sets
 * the display order, {@code comparison} adds the previous period's values. A
 * {@link ChartType#BREAKDOWN} additionally takes {@code subDimension} / {@code subLimit}: the top
 * sub-categories listed under each category.
 */
public record ChartDefinition(
        ChartType chartType,
        DimensionRef dimension,
        DimensionRef series,
        MeasureRef measure,
        List<FilterClause> filters,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer limit,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean other,
        @JsonInclude(JsonInclude.Include.NON_NULL) ChartOrder order,
        @JsonInclude(JsonInclude.Include.NON_NULL) Comparison comparison,
        @JsonInclude(JsonInclude.Include.NON_NULL) DimensionRef subDimension,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer subLimit
) implements ReportDefinition {

    public static final int MAX_LIMIT = 50;
    public static final int MAX_SUB_LIMIT = 10;
    public static final int DEFAULT_SUB_LIMIT = 3;

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public ChartDefinition {
    }

    /** A plain chart: no limit, comparison or breakdown options. */
    public ChartDefinition(ChartType chartType, DimensionRef dimension, DimensionRef series, MeasureRef measure,
                           List<FilterClause> filters) {
        this(chartType, dimension, series, measure, filters, null, null, null, null, null, null);
    }

    @Override
    public ReportType type() {
        return ReportType.CHART;
    }
}
