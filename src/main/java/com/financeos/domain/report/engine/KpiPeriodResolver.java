package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.cycle.BillingCycleService;
import com.financeos.domain.account.cycle.CycleOperators;
import com.financeos.domain.account.cycle.CycleWindows;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves the periods of a KPI: the current period (the definition's filters and the range they
 * select) and the previous period its comparison reads. Both KPI executors and the KPI's
 * underlying-data view take their filters from here, so the value shown and the rows behind it
 * can never disagree.
 *
 * <p>A previous period exists when the comparison is enabled (a missing comparison or a null
 * {@code enabled} counts as enabled) and both periods are bounded:
 * <ul>
 *   <li>a date operator: the current range is bounded, and the previous period is the calendar
 *       unit before it (this/previous week, month, year, FY) or the equal-length window before it
 *       (everything else); its filters swap the date filter for an explicit {@code between};</li>
 *   <li>a billing-cycle operator: the account has cycles, and the previous period is the cycle
 *       before; its filters swap the cycle filter for the internal {@code billing_cycles_ago}.</li>
 * </ul>
 */
@Component
public class KpiPeriodResolver {

    private final DateRangeResolver dateRangeResolver;
    @Nullable
    private final BillingCycleService billingCycleService;

    /**
     * @param billingCycleService null leaves every account without cycles, so billing-cycle
     *                            filters select nothing
     */
    public KpiPeriodResolver(DateRangeResolver dateRangeResolver, @Nullable BillingCycleService billingCycleService) {
        this.dateRangeResolver = dateRangeResolver;
        this.billingCycleService = billingCycleService;
    }

    /** The current and (when the KPI compares) previous period of {@code def} for {@code userId}. */
    public KpiPeriods resolve(KpiDefinition def, ReportDatasource datasource, UUID userId) {
        List<FilterClause> filters = def.filters() == null ? List.of() : def.filters();
        FilterClause dateFilter = dateRangeResolver.findDateFilter(datasource, filters);
        boolean compare = comparisonEnabled(def.comparison());

        DateRange current;
        KpiPeriods.Period previous = null;
        if (CycleOperators.isCycle(dateFilter)) {
            // Billing cycle: the report is limited to one account, whose cycle (and the one
            // before it) gives the range; the comparison steps back one cycle, not a flat shift.
            int cyclesAgo = CycleOperators.cyclesAgo(dateFilter);
            current = KpiReportExecutor.span(cycleWindows(userId, cyclesAgo, datasource, filters));
            if (compare && current.bounded()) {
                DateRange range = KpiReportExecutor.span(cycleWindows(userId, cyclesAgo + 1, datasource, filters));
                if (range.bounded()) {
                    previous = new KpiPeriods.Period(
                            KpiReportExecutor.withCyclesAgo(filters, dateFilter, cyclesAgo + 1), range);
                }
            }
        } else {
            current = dateRangeResolver.effectiveRange(dateFilter);
            if (compare && dateFilter != null && current.bounded()) {
                DateRange range = dateRangeResolver.previousPeriod(dateFilter.operator(), current);
                previous = new KpiPeriods.Period(withDateRange(filters, dateFilter, range), range);
            }
        }
        return new KpiPeriods(dateFilter, new KpiPeriods.Period(filters, current), previous);
    }

    /**
     * The user's cycle windows {@code cyclesAgo} cycles back, limited to the account the filters
     * pin the datasource's billing-cycle account field to (all accounts when they pin none).
     */
    public CycleWindows cycleWindows(UUID userId, int cyclesAgo, ReportDatasource datasource, List<FilterClause> filters) {
        if (billingCycleService == null) {
            return new CycleWindows(Map.of());
        }
        String accountRef = CycleOperators.singleAccountRef(datasource.billingCycleAccountField(), filters);
        return billingCycleService.windows(userId, cyclesAgo, AppTime.today(), accountRef);
    }

    /** A KPI compares unless its comparison is explicitly disabled. */
    static boolean comparisonEnabled(@Nullable Comparison comparison) {
        return comparison == null || comparison.enabled() == null || comparison.enabled();
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
        out.add(new FilterClause(dateFilter.field(), "between", value));
        return out;
    }
}
