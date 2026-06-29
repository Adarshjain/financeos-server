package com.financeos.domain.report;

/**
 * The kind of report. A single report is exactly one of these — they are never
 * mixed within one report. Each type has its own definition structure and its
 * own data-generation engine.
 */
public enum ReportType {
    KPI,
    CHART,
    TABLE
}
