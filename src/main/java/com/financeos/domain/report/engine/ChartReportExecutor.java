package com.financeos.domain.report.engine;

import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final KpiPeriodResolver periodResolver;

    public ChartReportExecutor(DateRangeResolver dateRangeResolver) {
        this(dateRangeResolver, null);
    }

    @Autowired
    public ChartReportExecutor(DateRangeResolver dateRangeResolver, BillingCycleService billingCycleService) {
        this.dateRangeResolver = dateRangeResolver;
        this.periodResolver = new KpiPeriodResolver(dateRangeResolver, billingCycleService);
    }

    @Transactional(readOnly = true)
    public ChartData execute(ChartDefinition def, ReportDatasource datasource, UUID userId) {
        ReportQueryBuilder queryBuilder = datasource.queryBuilder();
        List<FilterClause> filters = def.filters() == null ? List.of() : def.filters();

        // Pie/Donut/Breakdown render a single dimension as slices; any series split is ignored.
        DimensionRef seriesRef = ChartPeriods.ignoresSeries(def) ? null : def.series();
        DimensionRef dimRef = def.dimension();
        Aggregation agg = def.measure().aggregation();

        KpiPeriods periods = ChartPeriods.resolve(def, periodResolver, dateRangeResolver, datasource, userId);

        long[] rowCount = new long[1];
        ChartShaper.Grouped current = group(def, queryBuilder, datasource, dimRef, seriesRef, periods.current().filters(),
                userId, rowCount);
        ChartShaper.Grouped previous = periods.previousAvailable()
                ? group(def, queryBuilder, datasource, dimRef, seriesRef, periods.previous().filters(), userId, null)
                : null;
        Map<String, List<ChartShaper.SubCell>> subCells = null;
        if (def.chartType() == ChartType.BREAKDOWN && def.subDimension() != null) {
            subCells = subCells(def, queryBuilder, dimRef, def.subDimension(), periods.current().filters(), userId);
        }

        ChartShaper.Shaped shaped = ChartShaper.shape(def, agg, current, previous, subCells);
        ChartData.Meta meta = new ChartData.Meta(rowCount[0], ChartPeriods.view(periods.current().range()));
        return ChartPeriods.assemble(def, dimRef, shaped, meta, periods,
                valueLabels(datasource, dimRef), seriesRef != null ? valueLabels(datasource, seriesRef) : null,
                subCells != null ? valueLabels(datasource, def.subDimension()) : null);
    }

    /**
     * The grouped result over {@code filters}: one query by dimension (and series), with each
     * cell's row count and average alongside the aggregate. {@code rowCount} (when given) receives
     * the true contributing-row count.
     */
    private ChartShaper.Grouped group(ChartDefinition def, ReportQueryBuilder queryBuilder, ReportDatasource datasource,
            DimensionRef dimRef, DimensionRef seriesRef, List<FilterClause> filters, UUID userId, long[] rowCount) {
        boolean hasSeries = seriesRef != null;
        Set<String> joins = new HashSet<>();
        Map<String, Object> params = new HashMap<>();

        // Grouping expressions before the WHERE: a filter on a field the chart also groups by
        // (transactions category) then narrows the grouped rows too, not only the transactions.
        String dimSql = dimensionSql(queryBuilder, dimRef, joins);
        String measureExpr = queryBuilder.expression(def.measure().field(), joins);
        String aggFn = def.measure().aggregation().name();
        String seriesSql = hasSeries ? dimensionSql(queryBuilder, seriesRef, joins) : null;

        String where = queryBuilder.buildWhere(filters, userId, params, joins);

        if (rowCount != null) {
            // DISTINCT guards against dimension-join fan-out, e.g. the transactions category many-to-many.
            rowCount[0] = countRows(queryBuilder, joins, where, params, userId);
        }

        StringBuilder sql = new StringBuilder("SELECT ").append(dimSql).append(" AS dim");
        if (hasSeries) {
            sql.append(", ").append(seriesSql).append(" AS series_key");
        }
        sql.append(", ").append(aggFn).append("(").append(measureExpr).append(") AS val");
        sql.append(", COUNT(*) AS cnt, COUNT(").append(measureExpr).append(") AS mcnt, AVG(").append(measureExpr)
                .append(") AS avgv");
        sql.append(queryBuilder.fromClause(joins, params, userId)).append(where);
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

        ChartShaper.Builder builder = new ChartShaper.Builder();
        boolean dated = dimRef.granularity() != null;
        if (dated) {
            builder.dated();
        }
        int v = hasSeries ? 2 : 1;
        for (Object[] row : rows) {
            String category = label(dimRef, row[0]);
            String seriesName = hasSeries ? label(seriesRef, row[1]) : def.measure().field();
            builder.put(category, seriesName, new ChartShaper.Cell(
                    ResultValues.toBigDecimal(row[v]),
                    ((Number) row[v + 1]).longValue(),
                    ((Number) row[v + 2]).longValue(),
                    ResultValues.toBigDecimal(row[v + 3])));
            if (dated && row[0] != null) {
                builder.range(category, ChartShaper.bucketRange(ResultValues.toLocalDate(row[0]), dimRef.granularity()));
            }
        }
        return builder.build();
    }

    /** One grouped query by (dimension, sub-dimension): every category's sub-cells, no per-row queries. */
    private Map<String, List<ChartShaper.SubCell>> subCells(ChartDefinition def, ReportQueryBuilder queryBuilder,
            DimensionRef dimRef, DimensionRef subRef, List<FilterClause> filters, UUID userId) {
        Set<String> joins = new HashSet<>();
        Map<String, Object> params = new HashMap<>();
        String dimSql = dimensionSql(queryBuilder, dimRef, joins);
        String subSql = dimensionSql(queryBuilder, subRef, joins);
        String measureExpr = queryBuilder.expression(def.measure().field(), joins);
        String where = queryBuilder.buildWhere(filters, userId, params, joins);
        String sql = "SELECT " + dimSql + " AS dim, " + subSql + " AS sub_key, "
                + def.measure().aggregation().name() + "(" + measureExpr + ") AS val, COUNT(*) AS cnt"
                + queryBuilder.fromClause(joins, params, userId) + where
                + " GROUP BY " + dimSql + ", " + subSql + " ORDER BY " + dimSql + ", " + subSql;
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        Map<String, List<ChartShaper.SubCell>> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.computeIfAbsent(label(dimRef, row[0]), k -> new ArrayList<>()).add(new ChartShaper.SubCell(
                    label(subRef, row[1]), ResultValues.toBigDecimal(row[2]), ((Number) row[3]).longValue()));
        }
        return out;
    }

    private long countRows(ReportQueryBuilder queryBuilder, Set<String> whereJoins, String where, Map<String, Object> params, UUID userId) {
        String sql = "SELECT COUNT(DISTINCT " + queryBuilder.idExpression() + ")" + queryBuilder.fromClause(whereJoins, params, userId) + where;
        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    private String dimensionSql(ReportQueryBuilder queryBuilder, DimensionRef ref, Set<String> joins) {
        String expr = queryBuilder.expression(ref.field(), joins);
        return ref.granularity() != null ? queryBuilder.bucketExpression(expr, ref.granularity()) : expr;
    }

    /** The display labels of a plain (not date-bucketed) dimension's stored values; null when none. */
    static Map<String, String> valueLabels(ReportDatasource datasource, DimensionRef ref) {
        if (ref.granularity() != null) {
            return null;
        }
        var field = datasource.field(ref.field());
        return field != null ? field.valueLabels() : null;
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
