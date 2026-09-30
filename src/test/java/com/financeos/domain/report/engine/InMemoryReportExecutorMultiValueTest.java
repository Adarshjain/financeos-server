package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonPeriod;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory executor behaviour for null dimension values, multi-valued (List) fields, negated
 * enum operators over missing values, and the DateHint handed to the datasource.
 */
class InMemoryReportExecutorMultiValueTest {

    private InMemoryReportExecutor executor;
    private RecordingDatasource ds;

    @BeforeEach
    void setUp() {
        executor = new InMemoryReportExecutor(new DateRangeResolver(4));
        ds = new RecordingDatasource();
    }

    // ---------- helpers ----------

    private static Map<String, Object> row(Object cat, Object color, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("cat", cat);
        m.put("color", color);
        m.put("amount", new BigDecimal(amount));
        return m;
    }

    private static Map<String, Object> dated(String date, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("date", LocalDate.parse(date));
        m.put("amount", new BigDecimal(amount));
        return m;
    }

    private static ArrayNode arr(String... values) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        for (String v : values) a.add(v);
        return a;
    }

    private static FilterClause f(String field, String op, String value) {
        return new FilterClause(field, op, TextNode.valueOf(value));
    }

    private static FilterClause fIn(String field, String op, String... values) {
        return new FilterClause(field, op, arr(values));
    }

    private static FilterClause between(String from, String to) {
        ObjectNode n = JsonNodeFactory.instance.objectNode().put("from", from).put("to", to);
        return new FilterClause("date", "between", n);
    }

    private BigDecimal sum(FilterClause... filters) {
        return executor.execute(new KpiDefinition("amount", Aggregation.SUM, List.of(filters), null), ds, Map.of()).value();
    }

    private ChartData chart(String dim, String series, FilterClause... filters) {
        return executor.execute(new ChartDefinition(ChartType.BAR, new DimensionRef(dim, null),
                series == null ? null : new DimensionRef(series, null),
                new MeasureRef("amount", Aggregation.SUM), List.of(filters)), ds, Map.of());
    }

    // ---------- null dimension / series ----------

    @Test
    void chartNullDimensionValueBecomesNoneBar() {
        ds.rows = List.of(row("A", "red", "10"), row(null, "red", "5"));

        ChartData chart = chart("cat", null);

        assertEquals(List.of("(none)", "A"), chart.categories());
        assertEquals(List.of(new BigDecimal("5"), new BigDecimal("10")), chart.series().get(0).data());
    }

    @Test
    void chartNullSeriesValueBecomesNoneSeries() {
        ds.rows = List.of(row("A", "red", "10"), row("A", null, "4"));

        ChartData chart = chart("cat", "color");

        assertEquals(List.of("A"), chart.categories());
        assertEquals(2, chart.series().size());
        assertEquals("(none)", chart.series().get(0).name());
        assertEquals(List.of(new BigDecimal("4")), chart.series().get(0).data());
        assertEquals("red", chart.series().get(1).name());
        assertEquals(List.of(new BigDecimal("10")), chart.series().get(1).data());
    }

    // ---------- multi-valued filters ----------

    private void multiRows() {
        ds.rows = List.of(
                row(List.of("A", "B"), null, "1"),   // r1
                row(List.of("C"), null, "10"),       // r2
                row(List.of(), null, "100"),         // r3 empty list == no value
                row(null, null, "1000"),             // r4 no value
                row(List.of("A"), null, "10000"));   // r5
    }

    @Test
    void multiValuedIsMatchesWhenAnyElementMatches() {
        multiRows();
        assertEquals(new BigDecimal("10001"), sum(f("cat", "is", "A")));   // r1 + r5
        assertEquals(new BigDecimal("1"), sum(f("cat", "is", "B")));       // r1 via its second element
    }

    @Test
    void multiValuedInMatchesWhenAnyElementInSet() {
        multiRows();
        assertEquals(new BigDecimal("11"), sum(fIn("cat", "in", "B", "C")));  // r1 + r2
    }

    @Test
    void multiValuedIsNotRequiresNoElementToMatch() {
        multiRows();
        // r1 has an "A" (and a "B"): excluded. r2, r3 (empty), r4 (null), r5? r5 is A -> excluded.
        assertEquals(new BigDecimal("1110"), sum(f("cat", "is_not", "A")));
    }

    @Test
    void multiValuedNotInRequiresNoElementInSet() {
        multiRows();
        // r1(A), r2(C), r5(A) excluded; empty and null pass.
        assertEquals(new BigDecimal("1100"), sum(fIn("cat", "not_in", "A", "C")));
    }

    @Test
    void emptyListBehavesLikeNullForPositiveAndNegatedOperators() {
        ds.rows = List.of(row(List.of(), null, "7"));
        assertEquals(BigDecimal.ZERO, sum(f("cat", "is", "A")));
        assertEquals(BigDecimal.ZERO, sum(fIn("cat", "in", "A")));
        assertEquals(new BigDecimal("7"), sum(f("cat", "is_not", "A")));
        assertEquals(new BigDecimal("7"), sum(fIn("cat", "not_in", "A")));
    }

    @Test
    void multiValuedStringFilterMatchesAnyElement() {
        Map<String, Object> r1 = row(null, null, "3");
        r1.put("tagText", List.of("alpha", "beta"));
        Map<String, Object> r2 = row(null, null, "4");
        r2.put("tagText", List.of("gamma"));
        ds.rows = List.of(r1, r2);
        assertEquals(new BigDecimal("3"), sum(f("tagText", "contains", "bet")));
    }

    // ---------- enum is_not / not_in over missing scalar ----------

    @Test
    void enumNegatedOperatorsLetNullScalarPass() {
        ds.rows = List.of(row("A", "red", "1"), row("A", null, "10"), row("A", "blue", "100"));

        assertEquals(new BigDecimal("110"), sum(f("color", "is_not", "red")));
        assertEquals(new BigDecimal("110"), sum(fIn("color", "not_in", "red")));
    }

    @Test
    void enumPositiveOperatorsStillExcludeNullScalar() {
        ds.rows = List.of(row("A", "red", "1"), row("A", null, "10"));

        assertEquals(new BigDecimal("1"), sum(f("color", "is", "red")));
        assertEquals(new BigDecimal("1"), sum(fIn("color", "in", "red", "blue")));
    }

    // ---------- multi-valued grouping ----------

    @Test
    void chartCountsMultiValuedRowUnderEachElement() {
        ds.rows = List.of(row(List.of("A", "B"), "red", "10"), row(List.of("C"), "red", "5"));

        ChartData chart = chart("cat", null);

        assertEquals(List.of("A", "B", "C"), chart.categories());
        assertEquals(List.of(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("5")), chart.series().get(0).data());
    }

    @Test
    void chartDeduplicatesRepeatedElementsWithinOneRow() {
        ds.rows = List.of(row(List.of("A", "A"), "red", "10"));
        ChartData chart = chart("cat", null);
        assertEquals(List.of("A"), chart.categories());
        assertEquals(List.of(new BigDecimal("10")), chart.series().get(0).data());
    }

    @Test
    void chartEmptyListGroupsAsNone() {
        ds.rows = List.of(row(List.of(), "red", "10"), row(List.of("A"), "red", "1"));
        ChartData chart = chart("cat", null);
        assertEquals(List.of("(none)", "A"), chart.categories());
        assertEquals(List.of(new BigDecimal("10"), new BigDecimal("1")), chart.series().get(0).data());
    }

    @Test
    void chartMultiValuedSeriesFansOutAcrossSeries() {
        ds.rows = List.of(row(List.of("A", "B"), "red", "10"));
        ChartData chart = chart("color", "cat");
        assertEquals(List.of("red"), chart.categories());
        assertEquals(List.of("A", "B"), chart.series().stream().map(ChartData.Series::name).toList());
        assertEquals(List.of(new BigDecimal("10")), chart.series().get(0).data());
        assertEquals(List.of(new BigDecimal("10")), chart.series().get(1).data());
    }

    @Test
    void aggregatedTableFansOutOnRowsAndColumns() {
        Map<String, Object> r = row(List.of("A", "B"), "red", "10");
        r.put("tag", List.of("x", "y"));
        ds.rows = List.of(r);

        var data = executor.execute(new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("cat", null)), List.of(new DimensionRef("tag", null)),
                List.of(new MeasureRef("amount", Aggregation.SUM)), List.of(), List.of()), ds, Map.of());

        assertEquals(List.of("A", "B"), data.rows().stream().map(x -> x.key()).toList());
        assertEquals(List.of("x", "y"), data.columns().stream().map(c -> c.key()).toList());
        for (var row : data.rows()) {
            assertEquals(new BigDecimal("10"), row.cells().get("x").get("amount_sum"));
            assertEquals(new BigDecimal("10"), row.cells().get("y").get("amount_sum"));
        }
    }

    @Test
    void aggregatedTableRowFanOutWithoutColumns() {
        ds.rows = List.of(row(List.of("A", "B"), "red", "10"), row(List.of("B"), "red", "5"));

        var data = executor.execute(new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("cat", null)), List.of(),
                List.of(new MeasureRef("amount", Aggregation.SUM)), List.of(), List.of()), ds, Map.of());

        assertEquals(2, data.rows().size());
        assertEquals(new BigDecimal("10"), data.rows().get(0).cells().get("").get("amount_sum"));
        assertEquals(new BigDecimal("15"), data.rows().get(1).cells().get("").get("amount_sum"));
    }

    @Test
    void aggregatedTableNullDimensionGroupsAsNone() {
        ds.rows = List.of(row(null, "red", "10"));
        var data = executor.execute(new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("cat", null)), List.of(),
                List.of(new MeasureRef("amount", Aggregation.SUM)), List.of(), List.of()), ds, Map.of());
        assertEquals("(none)", data.rows().get(0).key());
    }

    // ---------- raw table display + sort ----------

    @Test
    void rawTableJoinsListsForDisplay() {
        ds.rows = List.of(
                withId("1", row(List.of("A", "C"), "red", "1")),
                withId("2", row(List.of("B"), "red", "2")),
                withId("3", row(List.of(), "red", "3")));

        TableData data = executor.execute(new RawTableDefinition(TableMode.RAW, List.of("cat"), List.of(),
                List.of(new SortClause("amount", SortDirection.ASC))), ds, Map.of(), 0, 10);

        assertEquals("A, C", data.rows().get(0).get("cat"));
        assertEquals("B", data.rows().get(1).get("cat"));
        assertNull(data.rows().get(2).get("cat"));   // empty list displays as no value
    }

    @Test
    void rawTableSortsMultiValuedFieldOnTheJoinedString() {
        ds.rows = List.of(
                withId("1", row(List.of("B"), "red", "1")),
                withId("2", row(List.of("A", "C"), "red", "1")),
                withId("3", row(List.of("A"), "red", "1")),
                withId("4", row(List.of(), "red", "1")));

        TableData data = executor.execute(new RawTableDefinition(TableMode.RAW, List.of("cat"), List.of(),
                List.of(new SortClause("cat", SortDirection.ASC))), ds, Map.of(), 0, 10);

        assertEquals(List.of("4", "3", "2", "1"), data.rows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void rawTableDefaultSortIsFirstDateFieldNewestFirst() {
        ds.rows = List.of(
                withId("old", dated("2026-01-05", "1")),
                withId("new", dated("2026-03-05", "1")),
                withId("mid", dated("2026-02-05", "1")));

        TableData data = executor.execute(new RawTableDefinition(TableMode.RAW, List.of("date"), List.of(), List.of()),
                ds, Map.of(), 0, 10);

        assertEquals(List.of("new", "mid", "old"), data.rows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void rawTableSortsTextColumnsAlphabetically() {
        ds.rows = List.of(withId("1", row("Travel", "red", "1")), withId("2", row("Dining", "red", "1")));

        TableData data = executor.execute(new RawTableDefinition(TableMode.RAW, List.of("cat"), List.of(),
                List.of(new SortClause("cat", SortDirection.DESC))), ds, Map.of(), 0, 10);

        assertEquals(List.of("1", "2"), data.rows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void rawTableSortsNumbersNumericallyNotAsText() {
        ds.rows = List.of(withId("nine", row("A", "red", "9")), withId("ten", row("A", "red", "10")));

        TableData data = executor.execute(new RawTableDefinition(TableMode.RAW, List.of("amount"), List.of(),
                List.of(new SortClause("amount", SortDirection.ASC))), ds, Map.of(), 0, 10);

        assertEquals(List.of("nine", "ten"), data.rows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void kpiComparisonWithNullEnabledIsTreatedAsDisabled() {
        ds.rows = List.of(dated("2026-05-10", "200"));

        KpiData result = executor.execute(new KpiDefinition("amount", Aggregation.SUM,
                List.of(between("2026-05-01", "2026-05-31")),
                new Comparison(null, ComparisonPeriod.PREVIOUS_PERIOD, true)), ds, Map.of());

        assertEquals(new BigDecimal("200"), result.value());
        assertNull(result.comparison());
    }

    private static Map<String, Object> withId(String id, Map<String, Object> row) {
        row.put("id", id);
        return row;
    }

    // ---------- DateHint ----------

    @Test
    void boundedDateFilterPassesHintToTheDatasource() {
        ds.rows = List.of(dated("2026-05-10", "1"));
        sum(between("2026-05-01", "2026-05-31"));

        assertEquals(1, ds.hints.size());
        assertEquals(new ComputedReportDatasource.DateHint("date", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)), ds.hints.get(0));
    }

    @Test
    void singleDayIsFilterPassesADayHint() {
        ds.rows = List.of(dated("2026-05-10", "1"));
        sum(f("date", "is", "2026-05-10"));
        assertEquals(new ComputedReportDatasource.DateHint("date", LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 10)), ds.hints.get(0));
    }

    @Test
    void hintIsPassedForChartAndBothTableKinds() {
        ds.rows = List.of(dated("2026-05-10", "1"));
        var expected = new ComputedReportDatasource.DateHint("date", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));

        chart("date", null, between("2026-05-01", "2026-05-31"));
        executor.execute(new RawTableDefinition(TableMode.RAW, List.of("amount"), List.of(between("2026-05-01", "2026-05-31")), List.of()),
                ds, Map.of(), 0, 10);
        executor.execute(new AggregatedTableDefinition(TableMode.AGGREGATED, List.of(new DimensionRef("date", null)), List.of(),
                List.of(new MeasureRef("amount", Aggregation.SUM)), List.of(between("2026-05-01", "2026-05-31")), List.of()),
                ds, Map.of());

        assertEquals(List.of(expected, expected, expected), ds.hints);
    }

    @Test
    void unboundedDateFilterPassesNullHint() {
        ds.rows = List.of(dated("2026-05-10", "1"));
        sum(new FilterClause("date", "all_time", null));
        sum(f("date", "after", "2026-01-01"));
        sum(f("date", "before", "2026-12-31"));

        assertEquals(3, ds.hints.size());
        assertTrue(ds.hints.stream().allMatch(h -> h == null));
    }

    @Test
    void noDateFilterPassesNullHint() {
        ds.rows = List.of(row("A", "red", "1"));
        sum(f("color", "is", "red"));
        sum();
        assertEquals(2, ds.hints.size());
        assertTrue(ds.hints.stream().allMatch(h -> h == null));
    }

    @Test
    void kpiComparisonHintSpansPreviousPeriodAndCurrent() {
        ds.rows = List.of(dated("2026-05-10", "200"), dated("2026-04-15", "100"));
        KpiData result = executor.execute(new KpiDefinition("amount", Aggregation.SUM,
                List.of(between("2026-05-01", "2026-05-31")),
                new Comparison(true, ComparisonPeriod.PREVIOUS_PERIOD, true)), ds, Map.of());

        // 31-day flat shift back: 2026-03-31 .. 2026-04-30
        assertEquals(LocalDate.of(2026, 3, 31), result.comparison().previousDateRange().from());
        assertEquals(new ComputedReportDatasource.DateHint("date", LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 31)),
                ds.hints.get(0));
        assertEquals(new BigDecimal("200"), result.value());
        assertEquals(new BigDecimal("100"), result.comparison().previousValue());
    }

    @Test
    void kpiComparisonDisabledKeepsHintToTheCurrentRange() {
        ds.rows = List.of(dated("2026-05-10", "200"));
        executor.execute(new KpiDefinition("amount", Aggregation.SUM, List.of(between("2026-05-01", "2026-05-31")),
                new Comparison(false, ComparisonPeriod.PREVIOUS_PERIOD, true)), ds, Map.of());
        assertEquals(new ComputedReportDatasource.DateHint("date", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),
                ds.hints.get(0));
    }

    @Test
    void datasourceReturningNullRowsIsTreatedAsEmpty() {
        ds.rows = null;
        assertEquals(BigDecimal.ZERO, sum());
    }

    // ---------- test datasource ----------

    private static class RecordingDatasource implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();
        final List<DateHint> hints = new ArrayList<>();

        @Override public String name() { return "mv"; }
        @Override public String label() { return "MV"; }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("cat", "Cat", FieldType.ENUM, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("tag", "Tag", FieldType.ENUM, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("color", "Color", FieldType.ENUM, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("tagText", "Tag text", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM, Aggregation.COUNT), null, null, List.of(), "currency"),
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, List.of()));
        }

        @Override
        public List<Map<String, Object>> rows() {
            return rows;
        }

        @Override
        public List<Map<String, Object>> rows(DateHint hint) {
            hints.add(hint);
            return rows;
        }
    }
}
