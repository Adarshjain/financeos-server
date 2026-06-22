package com.financeos.domain.report.definition;

/**
 * Period-over-period comparison configuration for KPI reports.
 *
 * <p>{@code higherIsBetter} drives the response {@code sentiment} (good/bad): when set, an
 * increase in the value is reported as "good" if true and "bad" if false (and vice versa for a
 * decrease). When null, sentiment is "neutral".
 */
public record Comparison(
        Boolean enabled,
        ComparisonPeriod period,
        Boolean higherIsBetter
) {}
