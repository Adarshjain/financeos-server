package com.financeos.domain.report.breakdown;

import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.engine.ReportData;
import org.springframework.lang.Nullable;

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
     * One page of one section in its default order (see {@link #section(UUID, String, int, int, SortClause)}).
     * Implemented by each kind rather than as a default method, so it runs in the bean's own transaction.
     */
    Optional<ReportData> section(UUID id, String section, int page, int size);

    /**
     * One page of one section, ordered by {@code sort} (null: the section's default order); empty
     * when this kind has no such row. Throws {@code ResourceNotFoundException} for a section the
     * row does not have, {@code ValidationException} for a sort key that is not one of its columns.
     */
    Optional<ReportData> section(UUID id, String section, int page, int size, @Nullable SortClause sort);
}
