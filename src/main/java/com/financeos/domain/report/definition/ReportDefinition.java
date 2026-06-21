package com.financeos.domain.report.definition;

import com.financeos.domain.report.ReportType;

/**
 * Sealed marker interface for the three report definition shapes.
 * Each concrete record maps to exactly one {@link ReportType} and is stored
 * as a JSON CLOB in {@code reports.definition}.
 */
public sealed interface ReportDefinition permits KpiDefinition, ChartDefinition, TableDefinition {

    /** The report type this definition belongs to. */
    ReportType type();
}
