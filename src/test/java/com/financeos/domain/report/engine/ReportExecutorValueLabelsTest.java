package com.financeos.domain.report.engine;

import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.TableMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every result shape carries a static enum field's value labels where it shows the field's values
 * (raw table columns, pivot row/column dimensions, chart categories and series), on both the
 * in-memory and the SQL executors; the values themselves stay as stored.
 */
class ReportExecutorValueLabelsTest {

    private static final List<ReportType> ALL = List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE);
    private static final Map<String, String> KINDS = DatasourceCatalog.valueLabels("bank_account", "Bank account", "loan", "Loan");
    private static final Map<String, String> SIDES = DatasourceCatalog.valueLabels("asset", "Asset", "liability", "Liability");

    private static final List<FieldDef> FIELDS = List.of(
            new FieldDef("name", "Name", FieldType.STRING, FieldRole.DIMENSION, null, null, null, ALL),
            new FieldDef("kind", "Kind", FieldType.ENUM, FieldRole.DIMENSION, null, List.of("bank_account", "loan"), null, ALL)
                    .withValueLabels(KINDS),
            new FieldDef("side", "Side", FieldType.ENUM, FieldRole.DIMENSION, null, List.of("asset", "liability"), null, ALL)
                    .withValueLabels(SIDES),
            new FieldDef("asOf", "As of", FieldType.DATE, FieldRole.DIMENSION, null, null, null, ALL),
            new FieldDef("value", "Value", FieldType.NUMBER, FieldRole.MEASURE, List.of(Aggregation.SUM), null, null, ALL, "currency"));

    // ------------------------------------------------------------------ in-memory

    private final ComputedReportDatasource computed = new ComputedReportDatasource() {
        @Override
        public String name() {
            return "labelled";
        }

        @Override
        public String label() {
            return "Labelled";
        }

        @Override
        public List<FieldDef> fields() {
            return FIELDS;
        }

        @Override
        public List<Map<String, Object>> rows() {
            return List.of(row("a", "Savings", "bank_account", "asset", "100"), row("b", "Car", "loan", "liability", "40"));
        }
    };

    private final InMemoryReportExecutor inMemory = new InMemoryReportExecutor(new DateRangeResolver(4));

    @Test
    void inMemoryRawTableLabelsTheEnumColumnsAndKeepsTheStoredValues() {
        TableData table = inMemory.execute(new RawTableDefinition(TableMode.RAW, List.of("name", "kind", "side", "value"),
                List.of(), null), computed, Map.of(), 0, 10);

        assertNull(column(table, "name").valueLabels());
        assertEquals(KINDS, column(table, "kind").valueLabels());
        assertEquals(SIDES, column(table, "side").valueLabels());
        assertNull(column(table, "value").valueLabels());
        assertEquals(List.of("bank_account", "loan"), table.rows().stream().map(r -> r.get("kind")).toList());
    }

    @Test
    void inMemoryPivotLabelsItsRowAndColumnDimensionsButNotADateBucket() {
        PivotTableData pivot = inMemory.execute(new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("side", null), new DimensionRef("asOf", Granularity.MONTH)),
                List.of(new DimensionRef("kind", null)), List.of(new MeasureRef("value", Aggregation.SUM)), List.of(), null),
                computed, Map.of(), 0, 10);

        assertEquals(SIDES, pivot.rowDimensions().get(0).valueLabels());
        assertNull(pivot.rowDimensions().get(1).valueLabels());
        assertEquals(KINDS, pivot.columnDimensions().get(0).valueLabels());
        assertEquals("asset", pivot.rows().get(0).values().get("side"));
    }

    @Test
    void inMemoryChartLabelsItsCategoriesAndSeries() {
        ChartData chart = inMemory.execute(new ChartDefinition(ChartType.BAR, new DimensionRef("kind", null),
                new DimensionRef("side", null), new MeasureRef("value", Aggregation.SUM), List.of()), computed, Map.of());

        assertEquals(KINDS, chart.valueLabels());
        assertEquals(SIDES, chart.seriesValueLabels());
        assertEquals(List.of("bank_account", "loan"), chart.categories());
    }

    @Test
    void inMemoryChartWithoutALabelledDimensionOrSeriesHasNoLabels() {
        ChartData byName = inMemory.execute(new ChartDefinition(ChartType.BAR, new DimensionRef("name", null), null,
                new MeasureRef("value", Aggregation.SUM), List.of()), computed, Map.of());
        // A pie ignores its series split, so it has no series labels either.
        ChartData pie = inMemory.execute(new ChartDefinition(ChartType.PIE, new DimensionRef("kind", null),
                new DimensionRef("side", null), new MeasureRef("value", Aggregation.SUM), List.of()), computed, Map.of());

        assertNull(byName.valueLabels());
        assertNull(byName.seriesValueLabels());
        assertEquals(KINDS, pie.valueLabels());
        assertNull(pie.seriesValueLabels());
    }

    // ------------------------------------------------------------------ SQL

    private final UUID userId = UUID.randomUUID();

    private ReportDatasource sqlDatasource() {
        ReportQueryBuilder qb = mock(ReportQueryBuilder.class);
        when(qb.buildWhere(any(), eq(userId), anyMap(), anySet())).thenReturn(" WHERE 1 = 1");
        when(qb.fromClause(anySet(), anyMap(), eq(userId))).thenReturn(" FROM t");
        when(qb.idExpression()).thenReturn("t.id");
        when(qb.expression(anyString(), anySet())).thenAnswer(inv -> "t." + inv.getArgument(0));
        when(qb.bucketExpression(anyString(), any())).thenAnswer(inv -> "TRUNC(" + inv.getArgument(0) + ")");
        ReportDatasource ds = mock(ReportDatasource.class);
        when(ds.name()).thenReturn("labelled_sql");
        when(ds.queryBuilder()).thenReturn(qb);
        when(ds.fields()).thenReturn(FIELDS);
        Map<String, FieldDef> byName = new HashMap<>();
        FIELDS.forEach(f -> byName.put(f.name(), f));
        when(ds.field(anyString())).thenAnswer(inv -> byName.get((String) inv.getArgument(0)));
        return ds;
    }

    private static EntityManager emReturning(List<Object[]> rows) {
        EntityManager em = mock(EntityManager.class);
        when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
            Query q = mock(Query.class);
            when(q.getSingleResult()).thenReturn((long) rows.size());
            when(q.getResultList()).thenReturn(rows);
            return q;
        });
        return em;
    }

    @Test
    void sqlRawTableLabelsTheEnumColumns() {
        TableReportExecutor executor = new TableReportExecutor(new DateRangeResolver(4));
        ReflectionTestUtils.setField(executor, "em", emReturning(List.<Object[]>of(new Object[]{"r1", "Savings", "bank_account"})));

        TableData table = (TableData) executor.execute(new RawTableDefinition(TableMode.RAW, List.of("name", "kind"),
                List.of(), null), sqlDatasource(), userId, 0, 10);

        assertNull(column(table, "name").valueLabels());
        assertEquals(KINDS, column(table, "kind").valueLabels());
        assertEquals("bank_account", table.rows().get(0).get("kind"));
    }

    @Test
    void sqlPivotLabelsItsRowAndColumnDimensionsButNotADateBucket() {
        TableReportExecutor executor = new TableReportExecutor(new DateRangeResolver(4));
        ReflectionTestUtils.setField(executor, "em", emReturning(List.<Object[]>of(
                new Object[]{"asset", java.sql.Date.valueOf(LocalDate.of(2026, 10, 1)), "bank_account", new BigDecimal("100")})));

        PivotTableData pivot = (PivotTableData) executor.execute(new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("side", null), new DimensionRef("asOf", Granularity.MONTH)),
                List.of(new DimensionRef("kind", null)), List.of(new MeasureRef("value", Aggregation.SUM)), List.of(), null),
                sqlDatasource(), userId, 0, 10);

        assertEquals(SIDES, pivot.rowDimensions().get(0).valueLabels());
        assertNull(pivot.rowDimensions().get(1).valueLabels());
        assertEquals(KINDS, pivot.columnDimensions().get(0).valueLabels());
    }

    @Test
    void sqlChartLabelsItsCategoriesAndSeries() {
        ChartReportExecutor executor = new ChartReportExecutor(new DateRangeResolver(4));
        ReflectionTestUtils.setField(executor, "em", emReturning(List.<Object[]>of(
                new Object[]{"bank_account", "asset", new BigDecimal("100")})));

        ChartData chart = executor.execute(new ChartDefinition(ChartType.BAR, new DimensionRef("kind", null),
                new DimensionRef("side", null), new MeasureRef("value", Aggregation.SUM), List.of()), sqlDatasource(), userId);
        ReflectionTestUtils.setField(executor, "em", emReturning(List.<Object[]>of(
                new Object[]{java.sql.Date.valueOf(LocalDate.of(2026, 10, 1)), new BigDecimal("100")})));
        ChartData byMonth = executor.execute(new ChartDefinition(ChartType.BAR, new DimensionRef("asOf", Granularity.MONTH),
                null, new MeasureRef("value", Aggregation.SUM), List.of()), sqlDatasource(), userId);

        assertEquals(KINDS, chart.valueLabels());
        assertEquals(SIDES, chart.seriesValueLabels());
        assertEquals(List.of("bank_account"), chart.categories());
        assertNull(byMonth.valueLabels());
        assertNull(byMonth.seriesValueLabels());
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> row(String id, String name, String kind, String side, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("kind", kind);
        m.put("side", side);
        m.put("asOf", LocalDate.of(2026, 10, 8));
        m.put("value", new BigDecimal(value));
        return m;
    }

    private static TableData.Column column(TableData table, String key) {
        return table.columns().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }
}
