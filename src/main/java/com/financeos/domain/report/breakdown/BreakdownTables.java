package com.financeos.domain.report.breakdown;

import com.financeos.domain.report.engine.TableData;

import java.util.List;
import java.util.Map;

/** Builders for the raw-table pages of breakdown sections (same shape as a raw table report). */
final class BreakdownTables {

    private BreakdownTables() {
    }

    /** One page whose rows were already fetched page-wise; {@code total} counts every row. */
    static TableData page(List<TableData.Column> columns, List<Map<String, Object>> pageRows, int page, int size,
                          long total) {
        int totalPages = total == 0 ? 1 : (int) ((total + size - 1) / size);
        return new TableData("TABLE", "raw", columns, pageRows, new TableData.Page(page, size, total, totalPages));
    }

    /** One page cut from every row, already in display order. */
    static TableData slice(List<TableData.Column> columns, List<Map<String, Object>> allRows, int page, int size) {
        int total = allRows.size();
        int from = (int) Math.min((long) page * size, total);
        int to = Math.min(from + size, total);
        return page(columns, List.copyOf(allRows.subList(from, to)), page, size, total);
    }
}
