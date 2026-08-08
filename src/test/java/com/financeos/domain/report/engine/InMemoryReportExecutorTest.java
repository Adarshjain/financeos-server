package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
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
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonPeriod;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableMode;
import com.financeos.domain.report.engine.PivotTableData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

class InMemoryReportExecutorTest {

    private InMemoryReportExecutor executor;
    private TestComputedDatasource datasource;

    @BeforeEach
    void setUp() {
        DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
        executor = new InMemoryReportExecutor(dateRangeResolver);
        datasource = new TestComputedDatasource();
    }

    @Test
    void nullSkippingAggregations() {
        datasource.setRows(List.of(
                Map.of("amount", new BigDecimal("10")),
                Map.of("id", "2") // amount is null
        ));

        KpiDefinition avgDef = new KpiDefinition("amount", Aggregation.AVG, List.of(), null);
        KpiData avgResult = executor.execute(avgDef, datasource, Map.of());
        // AVG of {10} (null skipped) = 10.0000
        assertEquals(new BigDecimal("10.0000"), avgResult.value());

        KpiDefinition countDef = new KpiDefinition("amount", Aggregation.COUNT, List.of(), null);
        KpiData countResult = executor.execute(countDef, datasource, Map.of());
        // COUNT of non-null amounts = 1
        assertEquals(new BigDecimal("1"), countResult.value());
    }

    @Test
    void filteringOperators() {
        datasource.setRows(List.of(
                Map.of("name", "Apple", "amount", new BigDecimal("100"), "active", true, "date", LocalDate.of(2026, 1, 15)),
                Map.of("name", "Banana", "amount", new BigDecimal("500"), "active", false, "date", LocalDate.of(2026, 6, 20))
        ));

        // String contains
        KpiDefinition filterContains = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("name", "contains", TextNode.valueOf("app"))), null);
        assertEquals(new BigDecimal("100"), executor.execute(filterContains, datasource, Map.of()).value());

        // Number greater_than
        KpiDefinition filterGt = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("amount", "greater_than", TextNode.valueOf("200"))), null);
        assertEquals(new BigDecimal("500"), executor.execute(filterGt, datasource, Map.of()).value());

        // Boolean is
        KpiDefinition filterBool = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("active", "is", BooleanNode.TRUE)), null);
        assertEquals(new BigDecimal("100"), executor.execute(filterBool, datasource, Map.of()).value());

        // Date between
        ObjectNode betweenVal = JsonNodeFactory.instance.objectNode()
                .put("from", "2026-06-01")
                .put("to", "2026-06-30");
        KpiDefinition filterDate = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("date", "between", betweenVal)), null);
        assertEquals(new BigDecimal("500"), executor.execute(filterDate, datasource, Map.of()).value());
    }

    @Test
    void kpiPeriodComparison() {
        datasource.setRows(List.of(
                Map.of("amount", new BigDecimal("200"), "date", LocalDate.of(2026, 5, 10)),
                Map.of("amount", new BigDecimal("100"), "date", LocalDate.of(2026, 4, 15))
        ));

        ObjectNode betweenMay = JsonNodeFactory.instance.objectNode()
                .put("from", "2026-05-01")
                .put("to", "2026-05-31");
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("date", "between", betweenMay)),
                new Comparison(true, ComparisonPeriod.PREVIOUS_PERIOD, true));

        KpiData result = executor.execute(def, datasource, Map.of());
        assertEquals(new BigDecimal("200"), result.value());
        assertNotNull(result.comparison());
        assertEquals(new BigDecimal("100"), result.comparison().previousValue());
        assertEquals(new BigDecimal("100"), result.comparison().change());
        assertEquals("up", result.comparison().direction());
        assertEquals("good", result.comparison().sentiment());
    }

    @Test
    void chartSeriesPivotingAndOrdering() {
        datasource.setRows(List.of(
                Map.of("date", LocalDate.of(2026, 2, 1), "category", "Tech", "amount", new BigDecimal("10")),
                Map.of("date", LocalDate.of(2026, 1, 1), "category", "Tech", "amount", new BigDecimal("20")),
                Map.of("date", LocalDate.of(2026, 1, 1), "category", "Health", "amount", new BigDecimal("30"))
        ));

        ChartDefinition def = new ChartDefinition(
                ChartType.BAR,
                new DimensionRef("date", Granularity.MONTH),
                new DimensionRef("category", null),
                new MeasureRef("amount", Aggregation.SUM),
                List.of()
        );

        ChartData chart = executor.execute(def, datasource, Map.of());
        assertEquals(List.of("Jan 26", "Feb 26"), chart.categories());
        assertEquals(2, chart.series().size());
        assertEquals("Health", chart.series().get(0).name());
        assertEquals(List.of(new BigDecimal("30"), BigDecimal.ZERO), chart.series().get(0).data());
        assertEquals("Tech", chart.series().get(1).name());
        assertEquals(List.of(new BigDecimal("20"), new BigDecimal("10")), chart.series().get(1).data());
    }

    @Test
    void rawTableSortingAndPagination() {
        datasource.setRows(List.of(
                Map.of("id", "a", "amount", new BigDecimal("100")),
                Map.of("id", "b", "amount", new BigDecimal("300")),
                Map.of("id", "c", "amount", new BigDecimal("200"))
        ));

        RawTableDefinition def = new RawTableDefinition(
                TableMode.RAW,
                List.of("amount"),
                List.of(),
                List.of(new SortClause("amount", SortDirection.DESC))
        );

        // Page numbers are 0-based, matching the SQL path and the client's pager.
        TableData data = executor.execute(def, datasource, Map.of(), 0, 2);
        assertEquals(2, data.rows().size());
        assertEquals("b", data.rows().get(0).get("id"));
        assertEquals("c", data.rows().get(1).get("id"));
        assertEquals(0, data.page().number());
        assertEquals(3, data.page().totalElements());
        assertEquals(2, data.page().totalPages());

        TableData lastPage = executor.execute(def, datasource, Map.of(), 1, 2);
        assertEquals(1, lastPage.rows().size());
        assertEquals("a", lastPage.rows().get(0).get("id"));
        assertEquals(1, lastPage.page().number());
    }

    @Test
    void aggregatedTablePivotShape() {
        datasource.setRows(List.of(
                Map.of("category", "Tech", "type", "BUY", "amount", new BigDecimal("100"))
        ));

        AggregatedTableDefinition def = new AggregatedTableDefinition(
                TableMode.AGGREGATED,
                List.of(new DimensionRef("category", null)),
                List.of(new DimensionRef("type", null)),
                List.of(new MeasureRef("amount", Aggregation.SUM)),
                List.of(),
                List.of()
        );

        PivotTableData data = executor.execute(def, datasource, Map.of());
        assertNotNull(data);
        assertEquals(1, data.rowDimensions().size());
        assertEquals(1, data.columnDimensions().size());
        assertEquals(1, data.rows().size());
        assertEquals("Tech", data.rows().get(0).values().get("category"));
    }

    private static class TestComputedDatasource implements ComputedReportDatasource {
        private List<Map<String, Object>> rows = List.of();

        public void setRows(List<Map<String, Object>> rows) {
            this.rows = rows;
        }

        @Override
        public String name() { return "test"; }

        @Override
        public String label() { return "Test"; }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("name", "Name", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("category", "Category", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("type", "Type", FieldType.STRING, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT), null, null, List.of(), "currency"),
                    new FieldDef("active", "Active", FieldType.BOOLEAN, FieldRole.FILTER, null, null, null, List.of()),
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, List.of())
            );
        }

        @Override
        public List<Map<String, Object>> rows() {
            return rows;
        }
    }
}
