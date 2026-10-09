package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** In-memory KPIs: comparison on by default, and the previous period read through {@link KpiPeriodResolver}. */
class InMemoryReportExecutorKpiPeriodsTest {

    private InMemoryReportExecutor executor;
    private Ds ds;

    @BeforeEach
    void setUp() {
        executor = new InMemoryReportExecutor(new DateRangeResolver(4));
        ds = new Ds();
    }

    private static Map<String, Object> row(LocalDate date, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("date", date);
        m.put("amount", new BigDecimal(amount));
        return m;
    }

    private static FilterClause may() {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("from", "2026-05-01").put("to", "2026-05-31");
        return new FilterClause("date", "between", value);
    }

    private KpiData run(Comparison comparison) {
        return executor.execute(new KpiDefinition("amount", Aggregation.SUM, List.of(may()), comparison), ds, Map.of());
    }

    @Test
    void aKpiWithoutAComparisonBlockComparesWithNeutralSentiment() {
        ds.rows = List.of(row(LocalDate.of(2026, 5, 10), "300"), row(LocalDate.of(2026, 4, 10), "100"));

        KpiData data = run(null);

        assertEquals(new BigDecimal("300"), data.value());
        assertEquals(new BigDecimal("100"), data.comparison().previousValue());
        assertEquals(new BigDecimal("200"), data.comparison().change());
        assertEquals("up", data.comparison().direction());
        assertEquals("neutral", data.comparison().sentiment());
        assertEquals(LocalDate.of(2026, 3, 31), data.comparison().previousDateRange().from());
        assertEquals(LocalDate.of(2026, 4, 30), data.comparison().previousDateRange().to());
    }

    @Test
    void anExplicitlyDisabledComparisonStaysOff() {
        ds.rows = List.of(row(LocalDate.of(2026, 5, 10), "300"), row(LocalDate.of(2026, 4, 10), "100"));

        assertNull(run(new Comparison(false, null, true)).comparison());
    }

    @Test
    void theCurrentAndPreviousPeriodsAreTheResolversRanges() {
        ds.rows = List.of(row(LocalDate.of(2026, 5, 1), "1"), row(LocalDate.of(2026, 5, 31), "2"),
                row(LocalDate.of(2026, 3, 31), "10"), row(LocalDate.of(2026, 4, 30), "20"),
                row(LocalDate.of(2026, 3, 30), "100"), row(LocalDate.of(2026, 6, 1), "1000"));

        KpiData data = run(null);

        assertEquals(new BigDecimal("3"), data.value());
        assertEquals(new BigDecimal("30"), data.comparison().previousValue());
        assertEquals(LocalDate.of(2026, 5, 1), data.meta().dateRange().from());
        assertEquals(LocalDate.of(2026, 5, 31), data.meta().dateRange().to());
    }

    @Test
    void rowsWithoutADateCountInNeitherPeriod() {
        ds.rows = List.of(row(LocalDate.of(2026, 5, 10), "300"), row(null, "5"));

        KpiData data = run(new Comparison(true, null, null));

        assertEquals(new BigDecimal("300"), data.value());
        assertEquals(BigDecimal.ZERO, data.comparison().previousValue());
    }

    private static class Ds implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();

        @Override public String name() { return "kpi_periods"; }
        @Override public String label() { return "KPI periods"; }
        @Override public List<Map<String, Object>> rows() { return rows; }

        @Override
        public List<FieldDef> fields() {
            return List.of(
                    new FieldDef("date", "Date", FieldType.DATE, FieldRole.DIMENSION, null, null, null, List.of()),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM), null, null, List.of(), "currency"));
        }
    }
}
