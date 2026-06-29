package com.financeos.domain.report.definition;

import com.financeos.domain.report.ReportType;

import java.util.List;

/**
 * A Table report, in one of two distinct shapes (discriminated by {@code mode}):
 * <ul>
 *   <li>{@link RawTableDefinition} — selected columns from the transaction list.</li>
 *   <li>{@link AggregatedTableDefinition} — a pivot: row dimensions × column dimensions × measures.</li>
 * </ul>
 * Page size is NOT part of the definition — it is supplied at run time.
 */
public sealed interface TableDefinition extends ReportDefinition
        permits RawTableDefinition, AggregatedTableDefinition {

    TableMode mode();

    List<FilterClause> filters();

    List<SortClause> sort();

    @Override
    default ReportType type() {
        return ReportType.TABLE;
    }
}
