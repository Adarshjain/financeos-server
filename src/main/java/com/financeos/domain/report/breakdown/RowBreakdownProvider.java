package com.financeos.domain.report.breakdown;

import com.financeos.domain.report.engine.ReportData;

/**
 * Explains how the rows of one report datasource are computed. Implementations are Spring beans,
 * one per datasource, and resolve rows for the current user only.
 */
public interface RowBreakdownProvider {

    /** The {@code ReportDatasource#name()} this provider explains. */
    String datasource();

    /**
     * The breakdown of one row, each section holding its first page of {@code size} rows.
     * Throws {@code ResourceNotFoundException} when the row does not exist or is not the user's.
     */
    RowBreakdownResponse breakdown(String rowId, int size);

    /**
     * One page of one section of a row's breakdown, as a raw table.
     * Throws {@code ResourceNotFoundException} for an unknown row or section.
     */
    ReportData section(String rowId, String section, int page, int size);
}
