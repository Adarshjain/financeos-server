package com.financeos.domain.report.breakdown;

import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.engine.TableData;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Builders for the raw-table pages of breakdown sections (same shape as a raw table report). */
final class BreakdownTables {

    private BreakdownTables() {
    }

    /** One page whose rows were already fetched page-wise; {@code total} counts every row. */
    static TableData page(List<TableData.Column> columns, List<Map<String, Object>> pageRows, int page, int size,
                          long total) {
        return page(columns, pageRows, page, size, total, null);
    }

    /** One page cut from every row, already in display order. */
    static TableData slice(List<TableData.Column> columns, List<Map<String, Object>> allRows, int page, int size) {
        return cut(columns, allRows, page, size, null);
    }

    /**
     * One page cut from every row ordered by {@code sort}, echoed on the page; without a sort, the
     * rows' own (default) order as {@link #slice}. The sort runs over every row before paging and is
     * stable, so rows tied on the sort column keep the default order, and pages never overlap. Rows
     * without a value sort last in either direction.
     *
     * @throws com.financeos.core.exception.ValidationException when the sort key is not one of {@code columns}
     */
    static TableData sorted(List<TableData.Column> columns, List<Map<String, Object>> allRows, @Nullable SortClause sort,
                            int page, int size) {
        if (sort == null) {
            return slice(columns, allRows, page, size);
        }
        requireColumn(columns, sort);
        List<Map<String, Object>> ordered = new ArrayList<>(allRows);
        ordered.sort(comparator(sort));
        return cut(columns, ordered, page, size, sort);
    }

    /** Rejects a sort on anything but one of the section's columns (400, as for report tables). */
    static void requireColumn(List<TableData.Column> columns, SortClause sort) {
        ReportDefinitionValidator.requireSortKey(sort, columns.stream().map(TableData.Column::key).toList());
    }

    /** Rows by {@code sort}'s column, nulls last in either direction; ties compare equal (List.sort is stable). */
    static Comparator<Map<String, Object>> comparator(SortClause sort) {
        boolean descending = sort.direction() == SortDirection.DESC;
        return (a, b) -> {
            Object x = a.get(sort.key());
            Object y = b.get(sort.key());
            if (x == null || y == null) {
                return x == null ? (y == null ? 0 : 1) : -1;
            }
            int cmp = compare(x, y);
            return descending ? -cmp : cmp;
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object x, Object y) {
        if (x instanceof Number && y instanceof Number) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString()));
        }
        if (x instanceof String s && y instanceof String t) {
            int cmp = String.CASE_INSENSITIVE_ORDER.compare(s, t);
            return cmp != 0 ? cmp : s.compareTo(t);
        }
        if (x instanceof Comparable c && x.getClass().isInstance(y)) {
            return c.compareTo(y);
        }
        return String.valueOf(x).compareTo(String.valueOf(y));
    }

    private static TableData cut(List<TableData.Column> columns, List<Map<String, Object>> allRows, int page, int size,
                                 @Nullable SortClause sort) {
        int total = allRows.size();
        int from = (int) Math.min((long) page * size, total);
        int to = Math.min(from + size, total);
        return page(columns, List.copyOf(allRows.subList(from, to)), page, size, total, sort);
    }

    private static TableData page(List<TableData.Column> columns, List<Map<String, Object>> pageRows, int page, int size,
                                  long total, @Nullable SortClause sort) {
        int totalPages = total == 0 ? 1 : (int) ((total + size - 1) / size);
        return new TableData("TABLE", "raw", columns, pageRows, new TableData.Page(page, size, total, totalPages),
                sort == null ? null : sort.key(), sort == null ? null : sort.direction().json());
    }
}
