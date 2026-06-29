package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.engine.TransactionQueryBuilder.Join;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Computes a KPI report: a single aggregated value plus an optional prior-period comparison. */
@Service
public class KpiReportExecutor {

    @PersistenceContext
    private EntityManager em;

    private final TransactionQueryBuilder queryBuilder;
    private final DateRangeResolver dateRangeResolver;

    public KpiReportExecutor(TransactionQueryBuilder queryBuilder, DateRangeResolver dateRangeResolver) {
        this.queryBuilder = queryBuilder;
        this.dateRangeResolver = dateRangeResolver;
    }

    @Transactional(readOnly = true)
    public KpiData execute(KpiDefinition def, UUID userId) {
        List<FilterClause> filters = def.filters() == null ? List.of() : def.filters();

        Aggregate main = runAggregate(def, filters, userId);

        FilterClause dateFilter = dateRangeResolver.findDateFilter(filters);
        DateRange currentRange = dateRangeResolver.effectiveRange(dateFilter);

        KpiData.Comparison comparison = null;
        if (comparisonEnabled(def.comparison()) && dateFilter != null && currentRange.bounded()) {
            DateRange previous = dateRangeResolver.previousPeriod(dateFilter.operator(), currentRange);
            if (previous.bounded()) {
                List<FilterClause> previousFilters = withDateRange(filters, dateFilter, previous);
                Aggregate prior = runAggregate(def, previousFilters, userId);
                Boolean higherIsBetter = def.comparison() == null ? null : def.comparison().higherIsBetter();
                comparison = buildComparison(main.value(), prior.value(), previous, higherIsBetter);
            }
        }

        KpiData.Meta meta = new KpiData.Meta(
                main.rowCount(),
                currentRange.bounded()
                        ? new KpiData.DateRangeView(currentRange.from(), currentRange.to())
                        : null);

        return new KpiData("KPI", main.value(), def.measure(), def.aggregation().json(), comparison, meta);
    }

    private record Aggregate(BigDecimal value, long rowCount) {
    }

    private Aggregate runAggregate(KpiDefinition def, List<FilterClause> filters, UUID userId) {
        Set<Join> joins = EnumSet.noneOf(Join.class);
        Map<String, Object> params = new HashMap<>();

        String measureExpr = queryBuilder.expression(def.measure(), joins);
        String aggFn = def.aggregation().name(); // SUM / AVG / COUNT / MIN / MAX
        String where = queryBuilder.buildWhere(filters, userId, params, joins);

        String sql = "SELECT " + aggFn + "(" + measureExpr + ") AS agg_value, COUNT(*) AS row_count"
                + queryBuilder.fromClause(joins) + where;

        Query query = em.createNativeQuery(sql);
        params.forEach(query::setParameter);

        Object[] row = (Object[]) query.getSingleResult();
        BigDecimal value = ResultValues.toBigDecimal(row[0]);
        long rowCount = ((Number) row[1]).longValue();

        if (value == null && (def.aggregation() == Aggregation.SUM || def.aggregation() == Aggregation.COUNT)) {
            value = BigDecimal.ZERO;
        }
        return new Aggregate(value, rowCount);
    }

    /** Replaces the date filter with an explicit BETWEEN over the given (previous) range. */
    private static List<FilterClause> withDateRange(List<FilterClause> filters, FilterClause dateFilter,
            DateRange range) {
        List<FilterClause> out = new ArrayList<>();
        for (FilterClause filter : filters) {
            if (filter != dateFilter) {
                out.add(filter);
            }
        }
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("from", range.from().toString());
        value.put("to", range.to().toString());
        out.add(new FilterClause("date", "between", value));
        return out;
    }

    private static boolean comparisonEnabled(Comparison comparison) {
        if (comparison == null) {
            return true; // comparison is on by default
        }
        return comparison.enabled() == null || comparison.enabled();
    }

    private static KpiData.Comparison buildComparison(BigDecimal current, BigDecimal previousValue,
            DateRange previousRange, Boolean higherIsBetter) {
        BigDecimal cur = current == null ? BigDecimal.ZERO : current;
        BigDecimal prev = previousValue == null ? BigDecimal.ZERO : previousValue;
        BigDecimal change = cur.subtract(prev);

        BigDecimal changePercent = null;
        if (prev.signum() != 0) {
            changePercent = change
                    .divide(prev.abs(), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        String direction = change.signum() > 0 ? "up" : change.signum() < 0 ? "down" : "flat";

        String sentiment;
        if (higherIsBetter == null || change.signum() == 0) {
            sentiment = "neutral";
        } else if (change.signum() > 0) {
            sentiment = higherIsBetter ? "good" : "bad";
        } else {
            sentiment = higherIsBetter ? "bad" : "good";
        }

        KpiData.DateRangeView previousView = new KpiData.DateRangeView(previousRange.from(), previousRange.to());
        return new KpiData.Comparison(prev, previousView, change, changePercent, direction, sentiment);
    }
}
