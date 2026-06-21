package com.financeos.domain.report.engine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The computed result of a Chart report, in a charting-library-friendly shape. */
public record ChartData(
        String type,
        String chartType,
        String dimension,
        List<String> categories,
        List<Series> series,
        MeasureView measure,
        Meta meta) implements ReportData {

    /** One plotted series; {@code data} is aligned by index to {@code categories}. */
    public record Series(String name, List<BigDecimal> data) {
    }

    public record MeasureView(String field, String aggregation) {
    }

    public record Meta(long rowCount, DateRangeView dateRange) {
    }

    public record DateRangeView(LocalDate from, LocalDate to) {
    }
}
