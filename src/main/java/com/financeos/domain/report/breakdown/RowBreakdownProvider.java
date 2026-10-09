package com.financeos.domain.report.breakdown;

import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import org.springframework.lang.Nullable;

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
     * One page of one section of a row's breakdown, as a raw table, in the section's default order.
     * Throws {@code ResourceNotFoundException} for an unknown row or section.
     */
    default ReportData section(String rowId, String section, int page, int size) {
        return section(rowId, section, page, size, null);
    }

    /**
     * One page of one section ordered by {@code sort} over the whole section (stable: ties keep the
     * default order; rows without a value last), the sort echoed on the page; null keeps the
     * default order. Throws {@code ResourceNotFoundException} for an unknown row or section, and
     * {@code ValidationException} when the sort key is not one of the section's columns.
     */
    ReportData section(String rowId, String section, int page, int size, @Nullable SortClause sort);
}
