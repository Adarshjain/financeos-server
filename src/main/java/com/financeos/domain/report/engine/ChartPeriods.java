package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Period resolution and result assembly the two chart executors share. */
final class ChartPeriods {

    private ChartPeriods() {
    }

    /** Pie, donut and breakdown render one dimension; a series split is ignored. */
    static boolean ignoresSeries(ChartDefinition def) {
        return def.chartType() == ChartType.PIE || def.chartType() == ChartType.DONUT
                || def.chartType() == ChartType.BREAKDOWN;
    }

    /**
     * The chart's current period and, when its comparison is on and a previous period resolves,
     * the previous one — through the KPI period logic (calendar-aligned or equal-length shifts,
     * the previous billing cycle). Without a comparison only the current period is resolved, as before.
     */
    static KpiPeriods resolve(ChartDefinition def, KpiPeriodResolver resolver, DateRangeResolver dateRangeResolver,
                              ReportDatasource datasource, UUID userId) {
        List<FilterClause> filters = def.filters() == null ? List.of() : def.filters();
        if (def.comparison() != null && KpiPeriodResolver.comparisonEnabled(def.comparison())) {
            return resolver.resolve(new KpiDefinition(def.measure().field(), def.measure().aggregation(), filters,
                    def.comparison()), datasource, userId);
        }
        FilterClause dateFilter = dateRangeResolver.findDateFilter(datasource, filters);
        return new KpiPeriods(dateFilter, new KpiPeriods.Period(filters, dateRangeResolver.effectiveRange(dateFilter)), null);
    }

    static ChartData.DateRangeView view(DateRange range) {
        return range.bounded() ? new ChartData.DateRangeView(range.from(), range.to()) : null;
    }

    static ChartData assemble(ChartDefinition def, DimensionRef dimRef, ChartShaper.Shaped shaped, ChartData.Meta meta,
                              KpiPeriods periods, Map<String, String> valueLabels, Map<String, String> seriesValueLabels,
                              Map<String, String> subValueLabels) {
        ChartData.Previous previous = shaped.previousSeries() == null ? null
                : new ChartData.Previous(shaped.previousSeries(), view(periods.previous().range()));
        return new ChartData("CHART", def.chartType().json(), dimRef.field(), shaped.categories(), shaped.series(),
                new ChartData.MeasureView(def.measure().field(), def.measure().aggregation().json()), meta,
                valueLabels, seriesValueLabels, previous, shaped.other(), shaped.breakdown(), shaped.categoryRanges(),
                subValueLabels);
    }
}
