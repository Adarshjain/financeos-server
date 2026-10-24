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
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> seriesValueLabels,
        @JsonInclude(JsonInclude.Include.NON_NULL) Previous previous,
        @JsonInclude(JsonInclude.Include.NON_NULL) Other other,
        @JsonInclude(JsonInclude.Include.NON_NULL) Breakdown breakdown,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<CategoryRange> categoryRanges,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> subValueLabels) implements ReportData {

    public ChartData(String type, String chartType, String dimension, List<String> categories, List<Series> series,
                     MeasureView measure, Meta meta, Map<String, String> valueLabels,
                     Map<String, String> seriesValueLabels, Previous previous, Other other, Breakdown breakdown,
                     List<CategoryRange> categoryRanges) {
        this(type, chartType, dimension, categories, series, measure, meta, valueLabels, seriesValueLabels, previous,
                other, breakdown, categoryRanges, null);
    }

    public ChartData(String type, String chartType, String dimension, List<String> categories, List<Series> series,
                     MeasureView measure, Meta meta) {
        this(type, chartType, dimension, categories, series, measure, meta, null, null, null, null, null, null);
    }

    public ChartData(String type, String chartType, String dimension, List<String> categories, List<Series> series,
                     MeasureView measure, Meta meta, Map<String, String> valueLabels,
                     Map<String, String> seriesValueLabels) {
        this(type, chartType, dimension, categories, series, measure, meta, valueLabels, seriesValueLabels,
                null, null, null, null);
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

    /**
     * The previous period's values, aligned index-for-index with {@code categories} and the same
     * series names (0 where the previous period has no value; the "Other" entry is the sum of
     * every category that was not kept).
     */
    public record Previous(List<Series> series, DateRangeView dateRange) {
    }

    /** Where the folded "Other" category sits in {@code categories}, and how many categories it holds. */
    public record Other(int index, int foldedCount) {
    }

    /**
 * A breakdown's ranked rows, aligned with {@code categories}; totals cover every category and are
 * null when the aggregation is not additive (AVG, MIN, MAX), where a share has no meaning.
 */
    public record Breakdown(List<BreakdownRow> rows, BigDecimal total, BigDecimal previousTotal) {
    }

    /** One breakdown row: source-row count, average of the measure, previous count, top sub-rows. */
    public record BreakdownRow(long count, BigDecimal avg, Long previousCount, List<SubRow> subRows) {
    }

    public record SubRow(String label, BigDecimal value, long count) {
    }

    /** Inclusive dates of one date-bucket category. */
    public record CategoryRange(LocalDate from, LocalDate to) {
    }
}
