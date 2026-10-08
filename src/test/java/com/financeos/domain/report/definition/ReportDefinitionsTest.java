package com.financeos.domain.report.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.ReportType;
import org.junit.jupiter.api.Test;

class ReportDefinitionsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesRawTableByMode() {
        String json = "{\"mode\":\"raw\",\"columns\":[\"date\",\"amount\"],\"filters\":[]}";
        ReportDefinition def = ReportDefinitions.parse(ReportType.TABLE, json, mapper);
        RawTableDefinition raw = assertInstanceOf(RawTableDefinition.class, def);
        assertEquals(2, raw.columns().size());
    }

    @Test
    void parsesAggregatedTableByMode() {
        String json = "{\"mode\":\"aggregated\","
                + "\"rows\":[{\"field\":\"date\",\"granularity\":\"month\"}],"
                + "\"columns\":[{\"field\":\"category\"}],"
                + "\"measures\":[{\"field\":\"amount\",\"aggregation\":\"sum\"}]}";
        ReportDefinition def = ReportDefinitions.parse(ReportType.TABLE, json, mapper);
        AggregatedTableDefinition agg = assertInstanceOf(AggregatedTableDefinition.class, def);
        assertEquals("date", agg.rows().get(0).field());
        assertEquals(Granularity.MONTH, agg.rows().get(0).granularity());
        assertEquals("category", agg.columns().get(0).field());
        assertEquals("amount", agg.measures().get(0).field());
    }

    @Test
    void rejectsMissingTableMode() {
        assertThrows(IllegalArgumentException.class,
                () -> ReportDefinitions.parse(ReportType.TABLE, "{\"columns\":[\"date\"]}", mapper));
    }

    @Test
    void parsesKpiComparisonWithHigherIsBetter() {
        String json = "{\"measure\":\"amount\",\"aggregation\":\"sum\","
                + "\"comparison\":{\"enabled\":true,\"period\":\"previous_period\",\"higherIsBetter\":false}}";
        ReportDefinition def = ReportDefinitions.parse(ReportType.KPI, json, mapper);
        KpiDefinition kpi = assertInstanceOf(KpiDefinition.class, def);
        assertEquals(Boolean.FALSE, kpi.comparison().higherIsBetter());
    }

    @Test
    void parsesKpiComparisonDisplay() {
        String json = "{\"measure\":\"amount\",\"aggregation\":\"sum\","
                + "\"comparison\":{\"enabled\":true,\"display\":\"previous_value\"}}";
        KpiDefinition kpi = assertInstanceOf(KpiDefinition.class, ReportDefinitions.parse(ReportType.KPI, json, mapper));
        assertEquals(ComparisonDisplay.PREVIOUS_VALUE, kpi.comparison().display());
        assertEquals(ComparisonDisplay.PREVIOUS_VALUE, ComparisonDisplay.resolve(kpi.comparison()));
    }

    @Test
    void kpiComparisonDisplayDefaultsToChangeForDefinitionsThatPredateIt() {
        String json = "{\"measure\":\"amount\",\"aggregation\":\"sum\","
                + "\"comparison\":{\"enabled\":true,\"higherIsBetter\":true}}";
        KpiDefinition kpi = assertInstanceOf(KpiDefinition.class, ReportDefinitions.parse(ReportType.KPI, json, mapper));
        assertNull(kpi.comparison().display());
        assertEquals(ComparisonDisplay.CHANGE, ComparisonDisplay.resolve(kpi.comparison()));
        assertEquals(ComparisonDisplay.CHANGE, ComparisonDisplay.resolve(null));
        // The three-arg convenience constructor leaves the display unset too.
        assertNull(new Comparison(true, null, null).display());
    }

    @Test
    void rejectsUnknownKpiComparisonDisplay() {
        String json = "{\"measure\":\"amount\",\"aggregation\":\"sum\","
                + "\"comparison\":{\"display\":\"delta\"}}";
        assertThrows(IllegalArgumentException.class, () -> ReportDefinitions.parse(ReportType.KPI, json, mapper));
    }

    @Test
    void kpiComparisonDisplayRoundTripsThroughStorageJson() {
        KpiDefinition def = new KpiDefinition("amount", com.financeos.domain.report.datasource.Aggregation.SUM,
                java.util.List.of(), new Comparison(true, null, null, ComparisonDisplay.PREVIOUS_VALUE));
        String json = ReportDefinitions.toJson(def, mapper);
        KpiDefinition back = assertInstanceOf(KpiDefinition.class, ReportDefinitions.parse(ReportType.KPI, json, mapper));
        assertEquals(ComparisonDisplay.PREVIOUS_VALUE, back.comparison().display());
    }
}
