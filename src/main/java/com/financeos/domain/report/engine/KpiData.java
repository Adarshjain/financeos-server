package com.financeos.domain.report.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The computed result of a KPI report. */
public record KpiData(
        String type,
        BigDecimal value,
        String measure,
        String aggregation,
        String format,
        Comparison comparison,
        Meta meta) implements ReportData {

    /**
     * Period-over-period comparison; null when disabled or the range is unbounded.
     *
     * <p>{@code previousValue} is null when the previous period had no rows for an aggregation
     * that yields no value without rows (AVG/MIN/MAX); {@code change} still treats that as zero.
     * {@code display} echoes the definition's choice ({@code change} / {@code previous_value}) so
     * the renderer knows which of the two to show on the line.
     */
    public record Comparison(
            BigDecimal previousValue,
            DateRangeView previousDateRange,
            BigDecimal change,
            BigDecimal changePercent,
            String direction,
            String sentiment,
            String display) {
    }

    public record Meta(long rowCount, DateRangeView dateRange) {
    }

    public record DateRangeView(LocalDate from, LocalDate to) {
    }
}
