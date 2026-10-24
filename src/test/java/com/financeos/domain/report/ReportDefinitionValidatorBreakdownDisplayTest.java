package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.BreakdownBars;
import com.financeos.domain.report.definition.BreakdownColumn;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonPeriod;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A breakdown chart's saved columns / bars: JSON round-trip and validation. */
class ReportDefinitionValidatorBreakdownDisplayTest {

    private final DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
    private final DatasourceRegistry registry = new DatasourceRegistry(List.of(
            new TransactionsDatasource(new SqlPredicates(dateRangeResolver), dateRangeResolver)), new DatasourceCatalog());
    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(registry);
    private final ObjectMapper mapper = new ObjectMapper();

    private static final DimensionRef CATEGORY = new DimensionRef("category", null);
    private static final MeasureRef SUM = new MeasureRef("amount", Aggregation.SUM);
    private static final Comparison COMPARISON = new Comparison(true, ComparisonPeriod.PREVIOUS_PERIOD, false);
    private static final Comparison DISABLED = new Comparison(false, ComparisonPeriod.PREVIOUS_PERIOD, false);

    private static ChartDefinition chart(ChartType type, Comparison cmp, List<BreakdownColumn> columns,
                                         BreakdownBars bars) {
        return new ChartDefinition(type, CATEGORY, null, SUM, List.of(), null, null, null, cmp, null, null, columns, bars);
    }

    private String rejected(ChartDefinition def) {
        return assertThrows(ValidationException.class, () -> validator.validate("transactions", def)).getMessage();
    }

    @Test
    void roundTripsColumnsAndBars() throws Exception {
        ChartDefinition def = chart(ChartType.BREAKDOWN, COMPARISON,
                List.of(BreakdownColumn.AMOUNT, BreakdownColumn.SHARE, BreakdownColumn.COUNT, BreakdownColumn.AVG,
                        BreakdownColumn.CHANGE), BreakdownBars.SHARE_OF_TOTAL);
        JsonNode json = mapper.valueToTree(def);
        assertEquals("share_of_total", json.get("bars").asText());
        assertEquals(List.of("amount", "share", "count", "avg", "change"),
                List.of(json.get("columns").get(0).asText(), json.get("columns").get(1).asText(),
                        json.get("columns").get(2).asText(), json.get("columns").get(3).asText(),
                        json.get("columns").get(4).asText()));
        assertEquals(def, mapper.treeToValue(json, ChartDefinition.class));

        ChartDefinition upper = mapper.readValue(
                "{\"chartType\":\"breakdown\",\"dimension\":{\"field\":\"category\"},\"measure\":{\"field\":\"amount\","
                        + "\"aggregation\":\"SUM\"},\"columns\":[\"AMOUNT\",\"Avg\"],\"bars\":\"RELATIVE_TO_LARGEST\"}",
                ChartDefinition.class);
        assertEquals(List.of(BreakdownColumn.AMOUNT, BreakdownColumn.AVG), upper.columns());
        assertEquals(BreakdownBars.RELATIVE_TO_LARGEST, upper.bars());
    }

    @Test
    void omitsUnsetFieldsFromJson() {
        JsonNode json = mapper.valueToTree(chart(ChartType.BREAKDOWN, null, null, null));
        assertFalse(json.has("columns"));
        assertFalse(json.has("bars"));
    }

    @Test
    void acceptsColumnsAndBarsOnABreakdown() {
        assertDoesNotThrow(() -> validator.validate("transactions", chart(ChartType.BREAKDOWN, null,
                List.of(BreakdownColumn.COUNT, BreakdownColumn.AMOUNT), BreakdownBars.SHARE_OF_TOTAL)));
        assertDoesNotThrow(() -> validator.validate("transactions",
                chart(ChartType.BREAKDOWN, COMPARISON, List.of(BreakdownColumn.CHANGE), null)));
        assertDoesNotThrow(() -> validator.validate("transactions", chart(ChartType.BREAKDOWN, null, null, null)));
    }

    @Test
    void rejectsColumnsAndBarsOnANonBreakdownChart() {
        assertEquals("columns is only valid for a breakdown chart",
                rejected(chart(ChartType.BAR, null, List.of(BreakdownColumn.AMOUNT), null)));
        assertEquals("bars is only valid for a breakdown chart",
                rejected(chart(ChartType.DONUT, null, null, BreakdownBars.SHARE_OF_TOTAL)));
    }

    @Test
    void rejectsEmptyOrDuplicateColumns() {
        assertEquals("columns must not be empty", rejected(chart(ChartType.BREAKDOWN, null, List.of(), null)));
        assertEquals("columns must not contain duplicates", rejected(chart(ChartType.BREAKDOWN, null,
                List.of(BreakdownColumn.AMOUNT, BreakdownColumn.SHARE, BreakdownColumn.AMOUNT), null)));
    }

    @Test
    void changeColumnNeedsComparisonEnabled() {
        List<BreakdownColumn> cols = List.of(BreakdownColumn.AMOUNT, BreakdownColumn.CHANGE);
        String msg = "the change column requires comparison to be enabled";
        assertEquals(msg, rejected(chart(ChartType.BREAKDOWN, null, cols, null)));
        assertEquals(msg, rejected(chart(ChartType.BREAKDOWN, DISABLED, cols, null)));
        assertTrue(rejected(chart(ChartType.BREAKDOWN, null, cols, null)).contains("change"));
    }

    @Test
    void anOldDefinitionWithoutTheFieldsStillValidates() throws Exception {
        ChartDefinition old = mapper.readValue(
                "{\"chartType\":\"breakdown\",\"dimension\":{\"field\":\"category\"},\"measure\":{\"field\":\"amount\","
                        + "\"aggregation\":\"SUM\"},\"filters\":[],\"limit\":5,\"order\":\"value_desc\"}",
                ChartDefinition.class);
        assertNull(old.columns());
        assertNull(old.bars());
        assertDoesNotThrow(() -> validator.validate("transactions", old));
        assertEquals(old, new ChartDefinition(old.chartType(), old.dimension(), old.series(), old.measure(),
                old.filters(), old.limit(), old.other(), old.order(), old.comparison(), old.subDimension(),
                old.subLimit()));
    }
}
