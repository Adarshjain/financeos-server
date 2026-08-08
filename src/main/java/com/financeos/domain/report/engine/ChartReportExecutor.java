package com.financeos.domain.report.engine;

import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Computes a Chart report: an aggregated measure across a dimension, optionally split by a
 * series dimension, pivoted into the {@code categories[]} / {@code series[]} shape.
 */
@Service
public class ChartReportExecutor {

    @PersistenceContext
    private EntityManager em;

    private final DateRangeResolver dateRangeResolver;

    public ChartReportExecutor(DateRangeResolver dateRangeResolver) {
        this.dateRangeResolver = dateRangeResolver;
    }

    @Transactional(readOnly = true)
    public ChartData execute(ChartDefinition def, ReportDatasource datasource, UUID userId) {
        ReportQueryBuilder queryBuilder = datasource.queryBuilder();
        List<FilterClause> filters = def.filters() == null ? List.of() : def.filters();

        // Pie/Donut render a single dimension as slices; any series split is ignored.
        boolean pie = def.chartType() == ChartType.PIE || def.chartType() == ChartType.DONUT;
        DimensionRef seriesRef = pie ? null : def.series();
        boolean hasSeries = seriesRef != null;

        Set<String> joins = new HashSet<>();
        Map<String, Object> params = new HashMap<>();
        String where = queryBuilder.buildWhere(filters, userId, params, joins);

        // True contributing-row count (DISTINCT guards against dimension-join fan-out,
        // e.g. the transactions category many-to-many).
        long rowCount = countRows(queryBuilder, joins, where, params);

        DimensionRef dimRef = def.dimension();
        String dimSql = dimensionSql(queryBuilder, dimRef, joins);
        String measureExpr = queryBuilder.expression(def.measure().field(), joins);
        String aggFn = def.measure().aggregation().name();
        String seriesSql = hasSeries ? dimensionSql(queryBuilder, seriesRef, joins) : null;

        StringBuilder sql = new StringBuilder("SELECT ").append(dimSql).append(" AS dim");
        if (hasSeries) {
            sql.append(", ").append(seriesSql).append(" AS series_key");
        }
        sql.append(", ").append(aggFn).append("(").append(measureExpr).append(") AS val");
        sql.append(queryBuilder.fromClause(joins)).append(where);
        sql.append(" GROUP BY ").append(dimSql);
        if (hasSeries) {
            sql.append(", ").append(seriesSql);
        }
        sql.append(" ORDER BY ").append(dimSql);
        if (hasSeries) {
            sql.append(", ").append(seriesSql);
        }

        Query query = em.createNativeQuery(sql.toString());
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return pivot(def, datasource, dimRef, seriesRef, hasSeries, rows, rowCount, filters);
    }

    private long countRows(ReportQueryBuilder queryBuilder, Set<String> whereJoins, String where, Map<String, Object> params) {
        String sql = "SELECT COUNT(DISTINCT " + queryBuilder.idExpression() + ")" + queryBuilder.fromClause(whereJoins) + where;
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    private String dimensionSql(ReportQueryBuilder queryBuilder, DimensionRef ref, Set<String> joins) {
        String expr = queryBuilder.expression(ref.field(), joins);
        return ref.granularity() != null ? queryBuilder.bucketExpression(expr, ref.granularity()) : expr;
    }

    private ChartData pivot(ChartDefinition def, ReportDatasource datasource, DimensionRef dimRef, DimensionRef seriesRef,
            boolean hasSeries, List<Object[]> rows, long rowCount, List<FilterClause> filters) {
        Aggregation agg = def.measure().aggregation();
        BigDecimal missing = (agg == Aggregation.SUM || agg == Aggregation.COUNT) ? BigDecimal.ZERO : null;

        List<String> categories = new ArrayList<>();
        Set<String> seenCategories = new HashSet<>();
        Map<String, Map<String, BigDecimal>> seriesValues = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String category = label(dimRef, row[0]);
            if (seenCategories.add(category)) {
                categories.add(category);
            }
            String seriesName;
            BigDecimal value;
            if (hasSeries) {
                seriesName = label(seriesRef, row[1]);
                value = ResultValues.toBigDecimal(row[2]);
            } else {
                seriesName = def.measure().field();
                value = ResultValues.toBigDecimal(row[1]);
            }
            seriesValues.computeIfAbsent(seriesName, k -> new HashMap<>()).put(category, value);
        }

        List<ChartData.Series> series = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> entry : seriesValues.entrySet()) {
            List<BigDecimal> data = new ArrayList<>(categories.size());
            for (String category : categories) {
                BigDecimal v = entry.getValue().get(category);
                data.add(v != null ? v : missing);
            }
            series.add(new ChartData.Series(entry.getKey(), data));
        }

        DateRange range = dateRangeResolver.effectiveRange(dateRangeResolver.findDateFilter(datasource, filters));
        ChartData.Meta meta = new ChartData.Meta(
                rowCount,
                range.bounded() ? new ChartData.DateRangeView(range.from(), range.to()) : null);

        return new ChartData(
                "CHART",
                def.chartType().json(),
                dimRef.field(),
                categories,
                series,
                new ChartData.MeasureView(def.measure().field(), agg.json()),
                meta);
    }

    private String label(DimensionRef ref, Object raw) {
        if (raw == null) {
            return "(none)";
        }
        if (ref.granularity() != null) {
            return BucketLabels.bucketLabel(ResultValues.toLocalDate(raw), ref.granularity(), dateRangeResolver.getFiscalYearStartMonth());
        }
        return String.valueOf(raw);
    }
}
