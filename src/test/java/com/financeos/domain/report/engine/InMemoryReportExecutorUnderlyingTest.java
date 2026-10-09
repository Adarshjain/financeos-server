package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-memory side of KPI underlying data: the listed rows of a period and their raw-table pages. */
class InMemoryReportExecutorUnderlyingTest {

    private InMemoryReportExecutor executor;
    private Ds ds;

    @BeforeEach
    void setUp() {
        executor = new InMemoryReportExecutor(new DateRangeResolver(4));
        ds = new Ds();
    }

    private static Map<String, Object> row(String id, LocalDate date, String kind, Object amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("date", date);
        m.put("kind", kind);
        m.put("amount", amount instanceof String s ? new BigDecimal(s) : amount);
        return m;
    }

    private static List<String> ids(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> String.valueOf(r.get("id"))).toList();
    }

    private static KpiDefinition kpi(Aggregation aggregation) {
        return new KpiDefinition("amount", aggregation, List.of(), null);
    }

    // ---- kpiRows ----

    @Test
    void rowsWithoutAMeasureAreNotListed() {
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 1), "x", "10"), row("b", LocalDate.of(2026, 5, 2), "x", null),
                row("c", LocalDate.of(2026, 5, 3), "y", 5));

        InMemoryReportExecutor.KpiRows rows = executor.kpiRows(kpi(Aggregation.SUM), ds, List.of());

        assertEquals(0, new BigDecimal("15").compareTo(rows.value()));
        assertEquals(List.of("a", "c"), ids(rows.rows()));
    }

    @Test
    void thePeriodFiltersSelectTheRows() {
        ds.rows = List.of(row("may", LocalDate.of(2026, 5, 10), "x", "10"), row("apr", LocalDate.of(2026, 4, 10), "x", "1"),
                row("y", LocalDate.of(2026, 5, 11), "y", "100"));
        List<FilterClause> filters = List.of(
                new FilterClause("date", "between", JsonNodeFactory.instance.objectNode().put("from", "2026-05-01").put("to", "2026-05-31")),
                new FilterClause("kind", "is", JsonNodeFactory.instance.textNode("x")));

        InMemoryReportExecutor.KpiRows rows = executor.kpiRows(kpi(Aggregation.SUM), ds, filters);

        assertEquals(new BigDecimal("10"), rows.value());
        assertEquals(List.of("may"), ids(rows.rows()));
    }

    @Test
    void maxListsEveryRowThatTiesForTheValue() {
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 1), "x", "70.0"), row("b", LocalDate.of(2026, 5, 2), "x", "70"),
                row("c", LocalDate.of(2026, 5, 3), "x", "12"), row("d", LocalDate.of(2026, 5, 4), "x", null));

        InMemoryReportExecutor.KpiRows rows = executor.kpiRows(kpi(Aggregation.MAX), ds, List.of());

        assertEquals(0, new BigDecimal("70").compareTo(rows.value()));
        assertEquals(List.of("a", "b"), ids(rows.rows()));
    }

    @Test
    void minListsOnlyTheSmallestRow() {
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 1), "x", "-3"), row("b", LocalDate.of(2026, 5, 2), "x", "4"));

        InMemoryReportExecutor.KpiRows rows = executor.kpiRows(kpi(Aggregation.MIN), ds, List.of());

        assertEquals(new BigDecimal("-3"), rows.value());
        assertEquals(List.of("a"), ids(rows.rows()));
    }

    @Test
    void minWithoutAnyMeasuredRowHasNoValueAndNoRows() {
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 1), "x", null));

        InMemoryReportExecutor.KpiRows rows = executor.kpiRows(kpi(Aggregation.MIN), ds, List.of());

        assertNull(rows.value());
        assertTrue(rows.rows().isEmpty());
    }

    @Test
    void theValueIsTheKpisValue() {
        ds.rows = List.of(row("a", LocalDate.of(2026, 5, 1), "x", "1"), row("b", LocalDate.of(2026, 5, 2), "x", "2"),
                row("c", LocalDate.of(2026, 5, 2), "x", null));
        for (Aggregation aggregation : Aggregation.values()) {
            assertEquals(executor.execute(kpi(aggregation), ds, Map.of()).value(),
                    executor.kpiRows(kpi(aggregation), ds, List.of()).value(), aggregation.name());
        }
    }

    // ---- rawTable ----

    @Test
    void rawTableOrdersByAnyFieldNotOnlyTheListedColumns() {
        List<Map<String, Object>> rows = List.of(row("a", LocalDate.of(2026, 5, 1), "y", "1"),
                row("b", LocalDate.of(2026, 5, 2), "x", "2"), row("c", LocalDate.of(2026, 5, 3), "y", "3"));

        TableData table = executor.rawTable(rows, List.of("date", "amount"),
                List.of(new SortClause("kind", SortDirection.ASC), new SortClause("amount", SortDirection.DESC)), ds, 0, 10);

        assertEquals(List.of("b", "c", "a"), ids(table.rows()));
        assertEquals(List.of("id", "date", "amount"), List.copyOf(table.rows().get(0).keySet()));
        assertEquals(List.of("Date", "Amount"), table.columns().stream().map(TableData.Column::label).toList());
    }

    @Test
    void rawTableWithoutASortListsTheNewestDateFirstAndKeepsTiesInOrder() {
        List<Map<String, Object>> rows = List.of(row("a", LocalDate.of(2026, 5, 1), "x", "1"),
                row("b", LocalDate.of(2026, 5, 3), "x", "2"), row("c", LocalDate.of(2026, 5, 3), "x", "3"));

        assertEquals(List.of("b", "c", "a"), ids(executor.rawTable(rows, List.of("amount"), List.of(), ds, 0, 10).rows()));
        assertEquals(List.of("b", "c", "a"), ids(executor.rawTable(rows, List.of("amount"), null, ds, 0, 10).rows()));
    }

    @Test
    void rawTableTakesThePageSizeAsGivenBeyondTheTableCap() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            rows.add(row("r" + i, LocalDate.of(2026, 5, 1), "x", "1"));
        }

        TableData table = executor.rawTable(rows, List.of("amount"), List.of(), ds, 0, 1500);

        assertEquals(1500, table.rows().size());
        assertEquals(new TableData.Page(0, 1500, 1500, 1), table.page());
    }

    @Test
    void rawTableClampsANonPositiveSizeAndANegativePage() {
        List<Map<String, Object>> rows = List.of(row("a", LocalDate.of(2026, 5, 2), "x", "1"),
                row("b", LocalDate.of(2026, 5, 1), "x", "2"));

        TableData table = executor.rawTable(rows, List.of("amount"), List.of(), ds, -2, 0);

        assertEquals(List.of("a"), ids(table.rows()));
        assertEquals(new TableData.Page(0, 1, 2, 2), table.page());
    }

    @Test
    void rawTablePagesFollowTheOrderAndARowWithoutIdUsesItsPosition() {
        Map<String, Object> noId = row(null, LocalDate.of(2026, 5, 1), "x", "1");
        noId.remove("id");
        List<Map<String, Object>> rows = List.of(row("a", LocalDate.of(2026, 5, 3), "x", "1"),
                row("b", LocalDate.of(2026, 5, 2), "x", "2"), noId);

        TableData second = executor.rawTable(rows, List.of("amount"), List.of(), ds, 1, 2);

        assertEquals(List.of("2"), ids(second.rows()));
        assertEquals(new TableData.Page(1, 2, 3, 2), second.page());
    }

    static class Ds implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String label() {
            return "Fake";
        }

        @Override
        public List<FieldDef> fields() {
            List<ReportType> all = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
            return List.of(
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, all),
                    new FieldDef("kind", "Kind", FieldType.ENUM, FieldRole.DIMENSION, null, List.of("x", "y"), null, all),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.values()), null, null, all, "currency"));
        }

        @Override
        public List<Map<String, Object>> rows() {
            return rows;
        }
    }
}
