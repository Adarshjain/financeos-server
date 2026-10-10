package com.financeos.domain.report.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.TableMode;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rows of a computed datasource name things by label (card) but the client links by id (cardId):
 * a raw table carries the id field of each id-backed column, and a pivot row the ids behind its
 * id-backed row dimensions.
 */
class InMemoryReportExecutorRowIdsTest {

    private final InMemoryReportExecutor executor = new InMemoryReportExecutor(new DateRangeResolver(4));
    private final Ds datasource = new Ds();

    private static Map<String, Object> row(String id, String card, String cardId, String kind, String amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("card", card);
        m.put("cardId", cardId);
        m.put("kind", kind);
        m.put("amount", new BigDecimal(amount));
        return m;
    }

    {
        datasource.rows = List.of(
                row("r1", "Gold", "card-gold", "A", "10"),
                row("r2", "Gold", "card-gold", "B", "5"),
                row("r3", "Silver", null, "A", "7"));
    }

    @Test
    void aRawTableCarriesTheIdOfAnIdBackedColumn() {
        TableData table = executor.execute(new RawTableDefinition(TableMode.RAW, List.of("card", "amount"), List.of(),
                List.of()), datasource, Map.of(), 0, 10);
        Map<String, Object> gold = table.rows().stream().filter(r -> "r1".equals(r.get("id"))).findFirst().orElseThrow();
        assertEquals("card-gold", gold.get("cardId"));
        Map<String, Object> silver = table.rows().stream().filter(r -> "r3".equals(r.get("id"))).findFirst().orElseThrow();
        assertNull(silver.get("cardId"));
        assertEquals(List.of("card", "amount"), table.columns().stream().map(TableData.Column::key).toList(),
                "the id is data, not a column");
    }

    @Test
    void aRawTableWithoutIdBackedColumnsCarriesNoIds() {
        TableData table = executor.execute(new RawTableDefinition(TableMode.RAW, List.of("kind", "amount"), List.of(),
                List.of()), datasource, Map.of(), 0, 10);
        table.rows().forEach(r -> assertFalse(r.containsKey("cardId")));
    }

    @Test
    void aPivotRowCarriesTheIdsOfItsIdBackedDimensions() throws Exception {
        PivotTableData pivot = executor.execute(new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("card", null)), List.of(),
                List.of(new MeasureRef("amount", Aggregation.SUM)), List.of(), List.of()), datasource, Map.of());
        PivotTableData.Row gold = pivot.rows().stream().filter(r -> "Gold".equals(r.key())).findFirst().orElseThrow();
        assertEquals(Map.of("cardId", "card-gold"), gold.ids());
        assertEquals(new BigDecimal("15"), gold.cells().get("").get("amount_sum"));
        PivotTableData.Row silver = pivot.rows().stream().filter(r -> "Silver".equals(r.key())).findFirst().orElseThrow();
        assertEquals(java.util.Collections.singletonMap("cardId", null), silver.ids());
        String json = new ObjectMapper().writeValueAsString(gold);
        assertEquals(true, json.contains("\"ids\":{\"cardId\":\"card-gold\"}"), json);
    }

    @Test
    void aPivotRowWithoutIdBackedDimensionsOmitsIds() throws Exception {
        PivotTableData pivot = executor.execute(new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("kind", null)), List.of(),
                List.of(new MeasureRef("amount", Aggregation.SUM)), List.of(), List.of()), datasource, Map.of());
        pivot.rows().forEach(r -> assertNull(r.ids()));
        assertFalse(new ObjectMapper().writeValueAsString(pivot.rows().get(0)).contains("\"ids\""));
    }

    private static class Ds implements ComputedReportDatasource {
        List<Map<String, Object>> rows = List.of();

        @Override public String name() { return "cards"; }
        @Override public String label() { return "Cards"; }
        @Override public List<Map<String, Object>> rows() { return rows; }

        @Override
        public List<FieldDef> fields() {
            List<ReportType> chartTable = List.of(ReportType.CHART, ReportType.TABLE);
            return List.of(
                    new FieldDef("card", "Card", FieldType.ENUM, FieldRole.DIMENSION, null, null, true, chartTable, null, "cardId"),
                    new FieldDef("kind", "Kind", FieldType.ENUM, FieldRole.DIMENSION, null, List.of("A", "B"), null, chartTable),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM), null, null, List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE), "currency"));
        }
    }
}
