package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.CycleOperators;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Computes a KPI report: a single aggregated value plus an optional prior-period comparison. */
@Service
public class KpiReportExecutor {

    @PersistenceContext
    private EntityManager em;

    private final KpiPeriodResolver periodResolver;

    public KpiReportExecutor(DateRangeResolver dateRangeResolver, BillingCycleService billingCycleService) {
        this.periodResolver = new KpiPeriodResolver(dateRangeResolver, billingCycleService);
    }

    @Transactional(readOnly = true)
    public KpiData execute(KpiDefinition def, ReportDatasource datasource, UUID userId) {
        ReportQueryBuilder queryBuilder = datasource.queryBuilder();
        KpiPeriods periods = periodResolver.resolve(def, datasource, userId);

        Aggregate main = runAggregate(def, queryBuilder, periods.current().filters(), userId);

        KpiData.Comparison comparison = null;
        if (periods.previousAvailable()) {
            Aggregate prior = runAggregate(def, queryBuilder, periods.previous().filters(), userId);
            comparison = KpiComparisons.build(main.value(), prior.value(), periods.previous().range(), def.comparison());
        }

        DateRange currentRange = periods.current().range();
        KpiData.Meta meta = new KpiData.Meta(
                main.rowCount(),
                currentRange.bounded()
                        ? new KpiData.DateRangeView(currentRange.from(), currentRange.to())
                        : null);

        var measureField = datasource.field(def.measure());
        String format = measureField != null ? measureField.format() : null;
        return new KpiData("KPI", main.value(), def.measure(), def.aggregation().json(), format, comparison, meta);
    }

    /**
     * The KPI's figure over {@code filters} — one period's filters from {@link KpiPeriodResolver} —
     * computed by the same query {@link #execute} runs, so it equals the KPI's value (current
     * period) or its comparison's previous value (previous period).
     */
    @Transactional(readOnly = true)
    public BigDecimal value(KpiDefinition def, ReportDatasource datasource, List<FilterClause> filters, UUID userId) {
        return runAggregate(def, datasource.queryBuilder(), filters, userId).value();
    }

    private record Aggregate(BigDecimal value, long rowCount) {
    }

    /** The range covered by cycle windows (one account's cycle in practice); unbounded when none. */
    static DateRange span(CycleWindows windows) {
        return windows.byAccount().isEmpty() ? DateRange.unbounded()
                : DateRange.of(windows.earliestStart(), windows.latestEnd());
    }

    /** Replaces the cycle filter with the same field, {@code cyclesAgo} cycles back. */
    static List<FilterClause> withCyclesAgo(List<FilterClause> filters, FilterClause dateFilter, int cyclesAgo) {
        List<FilterClause> out = new ArrayList<>();
        for (FilterClause filter : filters) {
            if (filter != dateFilter) {
                out.add(filter);
            }
        }
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("amount", cyclesAgo);
        out.add(new FilterClause(dateFilter.field(), CycleOperators.CYCLES_AGO, value));
        return out;
    }

    private Aggregate runAggregate(KpiDefinition def, ReportQueryBuilder queryBuilder, List<FilterClause> filters, UUID userId) {
        Set<String> joins = new HashSet<>();
        Map<String, Object> params = new HashMap<>();

        String measureExpr = queryBuilder.expression(def.measure(), joins);
        String aggFn = def.aggregation().name(); // SUM / AVG / COUNT / MIN / MAX
        String where = queryBuilder.buildWhere(filters, userId, params, joins);

        String sql = "SELECT " + aggFn + "(" + measureExpr + ") AS agg_value, COUNT(*) AS row_count"
                + queryBuilder.fromClause(joins, params, userId) + where;

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
}
