package com.financeos.domain.report.breakdown;

import com.financeos.domain.report.engine.ReportData;

import java.util.Optional;
import java.util.UUID;

/**
 * The breakdown of one kind of net worth row (accounts, loans, lendings). Each answers only for
 * ids of its own kind that are rows of the current user's net worth today, and is empty otherwise
 * so {@link NetWorthBreakdownProvider} can try the next kind.
 */
interface NetWorthItemBreakdown {

    /** The row's breakdown, sections holding their first page; empty when this kind has no such row. */
    Optional<RowBreakdownResponse> breakdown(UUID id, int size);

    /**
     * One page of one section; empty when this kind has no such row. Throws
     * {@code ResourceNotFoundException} for a section the row does not have.
     */
    Optional<ReportData> section(UUID id, String section, int page, int size);
}
