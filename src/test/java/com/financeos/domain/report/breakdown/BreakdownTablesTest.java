package com.financeos.domain.report.breakdown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.financeos.domain.report.engine.TableData;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BreakdownTablesTest {

    private static final List<TableData.Column> COLUMNS = List.of(new TableData.Column("n", "N", "number", null));

    @Test
    void pageIsARawTableWithTheGivenRowsAndTotals() {
        List<Map<String, Object>> rows = List.of(Map.of("id", "a", "n", 1));

        TableData table = BreakdownTables.page(COLUMNS, rows, 2, 10, 21);

        assertEquals("TABLE", table.type());
        assertEquals("raw", table.mode());
        assertEquals(COLUMNS, table.columns());
        assertEquals(rows, table.rows());
        assertEquals(new TableData.Page(2, 10, 21, 3), table.page());
    }

    @Test
    void anEmptyTableHasOnePage() {
        assertEquals(new TableData.Page(0, 25, 0, 1), BreakdownTables.page(COLUMNS, List.of(), 0, 25, 0).page());
    }

    @Test
    void exactMultipleOfTheSizeHasNoExtraPage() {
        assertEquals(4, BreakdownTables.page(COLUMNS, List.of(), 0, 5, 20).page().totalPages());
    }

    @Test
    void sliceCutsTheRequestedPageInOrder() {
        TableData table = BreakdownTables.slice(COLUMNS, rows(7), 1, 3);

        assertEquals(List.of(3, 4, 5), table.rows().stream().map(r -> r.get("n")).toList());
        assertEquals(new TableData.Page(1, 3, 7, 3), table.page());
    }

    @Test
    void sliceOfTheLastPageIsPartial() {
        assertEquals(List.of(6), BreakdownTables.slice(COLUMNS, rows(7), 2, 3).rows().stream().map(r -> r.get("n")).toList());
    }

    @Test
    void sliceBeyondTheLastPageIsEmptyButKeepsTheTotals() {
        TableData table = BreakdownTables.slice(COLUMNS, rows(7), 5, 3);

        assertEquals(List.of(), table.rows());
        assertEquals(new TableData.Page(5, 3, 7, 3), table.page());
    }

    private static List<Map<String, Object>> rows(int n) {
        return IntStream.range(0, n).<Map<String, Object>>mapToObj(i -> Map.of("id", String.valueOf(i), "n", i)).toList();
    }
}
