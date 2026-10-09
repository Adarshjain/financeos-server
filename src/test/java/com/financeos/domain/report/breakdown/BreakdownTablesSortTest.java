package com.financeos.domain.report.breakdown;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.engine.TableData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A breakdown section ordered by one of its columns: over every row before paging, stable (ties
 * keep the section's default order), rows without a value last in either direction, the sort
 * echoed on the page; a key that is not a column is a 400 with the report tables' message.
 */
class BreakdownTablesSortTest {

    private static final List<TableData.Column> COLUMNS = List.of(
            new TableData.Column("date", "Date", "date", null),
            new TableData.Column("name", "Name", "string", null),
            new TableData.Column("amount", "Amount", "number", "currency"),
            new TableData.Column("flag", "Flag", "boolean", null));

    /** Default (given) order: a, b, c, d, e. */
    private static List<Map<String, Object>> rows() {
        return List.of(
                row("a", LocalDate.of(2026, 10, 5), "banana", "30", true),
                row("b", LocalDate.of(2026, 10, 1), "Apple", null, false),
                row("c", LocalDate.of(2026, 10, 3), null, "10", true),
                row("d", LocalDate.of(2026, 10, 1), "cherry", "30", null),
                row("e", null, "apple", "2.5", false));
    }

    private static List<String> ids(TableData table) {
        return table.rows().stream().map(r -> (String) r.get("id")).toList();
    }

    private static TableData sorted(String key, SortDirection direction, int page, int size) {
        return BreakdownTables.sorted(COLUMNS, rows(), new SortClause(key, direction), page, size);
    }

    @Test
    void withoutASortTheRowsKeepTheirOrderAndNoSortIsEchoed() {
        TableData table = BreakdownTables.sorted(COLUMNS, rows(), null, 0, 10);

        assertEquals(List.of("a", "b", "c", "d", "e"), ids(table));
        assertNull(table.sortKey());
        assertNull(table.sortDirection());
    }

    @Test
    void numbersSortNumericallyWithTiesInTheDefaultOrderAndBlanksLast() {
        assertEquals(List.of("e", "c", "a", "d", "b"), ids(sorted("amount", SortDirection.ASC, 0, 10)));
        assertEquals(List.of("a", "d", "c", "e", "b"), ids(sorted("amount", SortDirection.DESC, 0, 10)));
    }

    @Test
    void datesSortChronologicallyWithBlanksLastInBothDirections() {
        assertEquals(List.of("b", "d", "c", "a", "e"), ids(sorted("date", SortDirection.ASC, 0, 10)));
        assertEquals(List.of("a", "c", "b", "d", "e"), ids(sorted("date", SortDirection.DESC, 0, 10)));
    }

    @Test
    void textSortsIgnoringCaseThenByCaseWithBlanksLast() {
        assertEquals(List.of("b", "e", "a", "d", "c"), ids(sorted("name", SortDirection.ASC, 0, 10)));
        assertEquals(List.of("d", "a", "e", "b", "c"), ids(sorted("name", SortDirection.DESC, 0, 10)));
    }

    @Test
    void booleansSortFalseFirstAscending() {
        assertEquals(List.of("b", "e", "a", "c", "d"), ids(sorted("flag", SortDirection.ASC, 0, 10)));
        assertEquals(List.of("a", "c", "b", "e", "d"), ids(sorted("flag", SortDirection.DESC, 0, 10)));
    }

    @Test
    void numbersOfDifferentTypesCompareByValue() {
        List<Map<String, Object>> mixed = List.of(row("x", null, null, null, null), row("y", null, null, null, null));
        mixed.get(0).put("amount", 7L);
        mixed.get(1).put("amount", new BigDecimal("6.5"));

        TableData table = BreakdownTables.sorted(COLUMNS, mixed, new SortClause("amount", SortDirection.ASC), 0, 10);

        assertEquals(List.of("y", "x"), ids(table));
    }

    @Test
    void theSortRunsOverEveryRowBeforePagingSoPagesNeverOverlap() {
        TableData first = sorted("amount", SortDirection.DESC, 0, 2);
        TableData second = sorted("amount", SortDirection.DESC, 1, 2);
        TableData third = sorted("amount", SortDirection.DESC, 2, 2);

        assertEquals(List.of("a", "d"), ids(first));
        assertEquals(List.of("c", "e"), ids(second));
        assertEquals(List.of("b"), ids(third));
        assertEquals(new TableData.Page(1, 2, 5, 3), second.page());
        List<String> all = new ArrayList<>(ids(first));
        all.addAll(ids(second));
        all.addAll(ids(third));
        assertEquals(List.of("a", "d", "c", "e", "b"), all);
    }

    @Test
    void thePageEchoesTheSort() {
        TableData table = sorted("name", SortDirection.DESC, 0, 10);

        assertEquals("name", table.sortKey());
        assertEquals("desc", table.sortDirection());
        assertEquals(COLUMNS, table.columns());
    }

    @Test
    void aKeyThatIsNotAColumnIsRejectedLikeAReportTableSort() {
        ValidationException e = assertThrows(ValidationException.class, () -> sorted("id", SortDirection.ASC, 0, 10));

        assertEquals("Sort key is not an available column: id", e.getMessage());
        assertThrows(ValidationException.class, () -> BreakdownTables.requireColumn(COLUMNS, new SortClause("nope", SortDirection.ASC)));
    }

    @Test
    void theRowsGivenAreNotReordered() {
        List<Map<String, Object>> given = new ArrayList<>(rows());

        BreakdownTables.sorted(COLUMNS, given, new SortClause("amount", SortDirection.ASC), 0, 10);

        assertEquals(List.of("a", "b", "c", "d", "e"), given.stream().map(r -> r.get("id")).toList());
    }

    private static Map<String, Object> row(String id, LocalDate date, String name, String amount, Boolean flag) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("date", date);
        m.put("name", name);
        m.put("amount", amount == null ? null : new BigDecimal(amount));
        m.put("flag", flag);
        return m;
    }
}
