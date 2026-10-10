package com.financeos.domain.report.underlying;

import com.financeos.domain.report.engine.ReportData;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The rows behind one period of a KPI ("View underlying data").
 *
 * <p>{@code value} is the aggregate over exactly the listed rows and always equals the KPI's own
 * figure for that period ({@code KpiData.value} for {@code current},
 * {@code KpiData.comparison.previousValue} for {@code previous}). For MIN/MAX
 * ({@code winnerOnly}) only the row(s) whose measure equals that value are listed.
 *
 * @param period            {@code current} or {@code previous}
 * @param datasource        the KPI's datasource name (e.g. {@code transactions}); breakdown views are keyed by it
 * @param range             this period's window; null when unbounded
 * @param previousAvailable whether the KPI has a previous period (it would show a comparison)
 * @param previousRange     the previous period's window; null when there is none or it is unbounded
 * @param measure           the KPI's measure field
 * @param measureLabel      the measure field's display label
 * @param aggregation       JSON form of the aggregation, e.g. {@code sum}
 * @param format            {@code currency} / {@code number} / {@code percent}, or null
 * @param value             the aggregate over the listed rows (null when there are none and the
 *                          aggregation yields no value without rows)
 * @param rowCount          total rows listed across all pages
 * @param winnerOnly        true for MIN/MAX: only the winning row(s) are listed
 * @param summaryLines      datasource-specific totals over all listed rows (empty when none); kept for
 *                          compatibility, the client shows {@code groupTotals} instead
 * @param filters           one human-readable chip per KPI filter clause (internal clauses excluded)
 * @param rowAction         {@code transaction} / {@code breakdown} when rows open a detail view, else null
 * @param groupField        field the client groups rows by while no runtime sort is active, else null
 * @param groupTotals       with a {@code groupField}: each group's figure (the KPI's aggregation of
 *                          its measure over every listed row of that group, all pages), keyed by
 *                          the group field's raw value; empty when there is no grouping
 * @param notCounted        items left out of the figure on purpose or by failure (empty when none)
 * @param sortKey           echo of the runtime sort key; null when the default order applies
 * @param sortDirection     echo of the runtime sort direction ({@code asc}/{@code desc}); null by default
 * @param table             one page of the listed rows as a raw table (each row carries a hidden {@code id})
 */
public record KpiUnderlyingResponse(
        String period,
        String datasource,
        @Nullable UnderlyingRange range,
        boolean previousAvailable,
        // @Schema as well: the spec loses @Nullable on a second property of the same component type.
        @Nullable @Schema(nullable = true) UnderlyingRange previousRange,
        String measure,
        String measureLabel,
        String aggregation,
        @Nullable String format,
        @Nullable BigDecimal value,
        long rowCount,
        boolean winnerOnly,
        List<UnderlyingSummaryLine> summaryLines,
        List<UnderlyingFilterChip> filters,
        @Nullable String rowAction,
        @Nullable String groupField,
        Map<String, BigDecimal> groupTotals,
        List<UnderlyingExcludedItem> notCounted,
        @Nullable String sortKey,
        @Nullable String sortDirection,
        ReportData table) {

    /** A response without grouping (no group totals). */
    public KpiUnderlyingResponse(String period, String datasource, @Nullable UnderlyingRange range,
            boolean previousAvailable, @Nullable UnderlyingRange previousRange, String measure, String measureLabel,
            String aggregation, @Nullable String format, @Nullable BigDecimal value, long rowCount, boolean winnerOnly,
            List<UnderlyingSummaryLine> summaryLines, List<UnderlyingFilterChip> filters, @Nullable String rowAction,
            @Nullable String groupField, List<UnderlyingExcludedItem> notCounted, @Nullable String sortKey,
            @Nullable String sortDirection, ReportData table) {
        this(period, datasource, range, previousAvailable, previousRange, measure, measureLabel, aggregation, format,
                value, rowCount, winnerOnly, summaryLines, filters, rowAction, groupField, Map.of(), notCounted,
                sortKey, sortDirection, table);
    }
}
