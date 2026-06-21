package com.financeos.domain.report.definition;

/**
 * Period-over-period comparison configuration for KPI reports.
 */
public record Comparison(
        Boolean enabled,
        ComparisonPeriod period
) {}
