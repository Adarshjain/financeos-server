package com.financeos.domain.report.engine;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The computed result of a Chart report, in a charting-library-friendly shape.
 * {@code valueLabels} / {@code seriesValueLabels} (static enum dimensions only, else omitted) say
 * how each stored value in {@code categories} / the series names reads for people.
 */
public record ChartData(
        String type,
        String chartType,
        String dimension,
        List<String> categories,
        List<Series> series,
        MeasureView measure,
        Meta meta,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> valueLabels,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> seriesValueLabels) implements ReportData {

    public ChartData(String type, String chartType, String dimension, List<String> categories, List<Series> series,
                     MeasureView measure, Meta meta) {
        this(type, chartType, dimension, categories, series, measure, meta, null, null);
    }

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
