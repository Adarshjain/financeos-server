package com.financeos.domain.report.definition;

import java.util.List;

/** A raw table: chosen columns from the transaction list, one row per transaction. */
public record RawTableDefinition(
        TableMode mode,
        List<String> columns,
        List<FilterClause> filters,
        List<SortClause> sort
) implements TableDefinition {
}
