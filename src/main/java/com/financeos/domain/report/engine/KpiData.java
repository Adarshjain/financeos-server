package com.financeos.domain.report.engine;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The computed result of a KPI report. */
public record KpiData(
        String type,
        BigDecimal value,
        String measure,
        String aggregation,
        Comparison comparison,
        Meta meta) implements ReportData {

    /** Period-over-period comparison; null when disabled or the range is unbounded. */
    public record Comparison(
            BigDecimal previousValue,
            BigDecimal change,
            BigDecimal changePercent,
            String direction) {
    }

    public record Meta(long rowCount, DateRangeView dateRange) {
    }

    public record DateRangeView(LocalDate from, LocalDate to) {
    }
}
