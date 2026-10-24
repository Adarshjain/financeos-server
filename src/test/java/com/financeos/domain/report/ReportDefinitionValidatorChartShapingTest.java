package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartOrder;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.Comparison;
import com.financeos.domain.report.definition.ComparisonPeriod;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validation of a chart's limit / other / order / comparison / breakdown options. */
class ReportDefinitionValidatorChartShapingTest {

    private final DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
    private final DatasourceRegistry registry = new DatasourceRegistry(List.of(
            new TransactionsDatasource(new SqlPredicates(dateRangeResolver), dateRangeResolver)), new DatasourceCatalog());
    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(registry);

    private static final DimensionRef CATEGORY = new DimensionRef("category", null);
    private static final DimensionRef ACCOUNT = new DimensionRef("account", null);
    private static final MeasureRef SUM = new MeasureRef("amount", Aggregation.SUM);
    private static final Comparison COMPARISON = new Comparison(true, ComparisonPeriod.PREVIOUS_PERIOD, false);

    private static ChartDefinition chart(ChartType type, DimensionRef dim, Integer limit, Boolean other, Comparison cmp,
                                         DimensionRef sub, Integer subLimit) {
        return new ChartDefinition(type, dim, null, SUM, List.of(), limit, other, null, cmp, sub, subLimit);
    }

    private String rejected(ChartDefinition def) {
        return assertThrows(ValidationException.class, () -> validator.validate("transactions", def)).getMessage();
    }

    @Test
    void acceptsLimitOtherOrderAndComparisonOnAnyChart() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR, CATEGORY, null, SUM, List.of(), 5, true,
                ChartOrder.VALUE_DESC, COMPARISON, null, null);
        assertDoesNotThrow(() -> validator.validate("transactions", def));
        assertDoesNotThrow(() -> validator.validate("transactions", chart(ChartType.DONUT, CATEGORY, 1, null, null, null, null)));
        assertDoesNotThrow(() -> validator.validate("transactions", chart(ChartType.BAR, CATEGORY, 50, null, null, null, null)));
    }

    @Test
    void acceptsABreakdownWithSubRows() {
        assertDoesNotThrow(() -> validator.validate("transactions",
                chart(ChartType.BREAKDOWN, CATEGORY, 8, true, COMPARISON, ACCOUNT, 10)));
        assertDoesNotThrow(() -> validator.validate("transactions",
                chart(ChartType.BREAKDOWN, CATEGORY, null, null, null, ACCOUNT, 1)));
    }

    @Test
    void rejectsLimitOutOfRange() {
        assertTrue(rejected(chart(ChartType.BAR, CATEGORY, 0, null, null, null, null)).contains("limit"));
        assertTrue(rejected(chart(ChartType.BAR, CATEGORY, 51, null, null, null, null)).contains("limit"));
        assertTrue(rejected(chart(ChartType.BAR, CATEGORY, -3, null, null, null, null)).contains("limit"));
    }

    @Test
    void rejectsOtherWithoutLimit() {
        assertTrue(rejected(chart(ChartType.BAR, CATEGORY, null, true, null, null, null)).contains("other"));
        assertDoesNotThrow(() -> validator.validate("transactions", chart(ChartType.BAR, CATEGORY, null, false, null, null, null)));
    }

    @Test
    void rejectsSubDimensionAndSubLimitOnANonBreakdownChart() {
        assertTrue(rejected(chart(ChartType.BAR, CATEGORY, null, null, null, ACCOUNT, null)).contains("subDimension"));
        assertTrue(rejected(chart(ChartType.DONUT, CATEGORY, null, null, null, null, 3)).contains("subLimit"));
    }

    @Test
    void rejectsSubLimitOutOfRange() {
        assertTrue(rejected(chart(ChartType.BREAKDOWN, CATEGORY, null, null, null, ACCOUNT, 0)).contains("subLimit"));
        assertTrue(rejected(chart(ChartType.BREAKDOWN, CATEGORY, null, null, null, ACCOUNT, 11)).contains("subLimit"));
    }

    @Test
    void rejectsSubDimensionEqualToTheDimension() {
        assertTrue(rejected(chart(ChartType.BREAKDOWN, CATEGORY, null, null, null, CATEGORY, null)).contains("subDimension"));
    }

    @Test
    void rejectsASubDimensionThatIsNotAllowedAsADimension() {
        // amount is a measure, not a dimension
        assertTrue(rejected(chart(ChartType.BREAKDOWN, CATEGORY, null, null, null, new DimensionRef("amount", null), null))
                .contains("not a dimension"));
        assertTrue(rejected(chart(ChartType.BREAKDOWN, CATEGORY, null, null, null, new DimensionRef("nope", null), null))
                .contains("Unknown field"));
    }

    @Test
    void aDateSubDimensionNeedsGranularity() {
        assertTrue(rejected(chart(ChartType.BREAKDOWN, CATEGORY, null, null, null, new DimensionRef("date", null), null))
                .contains("granularity"));
        assertDoesNotThrow(() -> validator.validate("transactions",
                chart(ChartType.BREAKDOWN, CATEGORY, null, null, null, new DimensionRef("date", Granularity.MONTH), null)));
    }

    @Test
    void rejectsAComparisonWithADateDimension() {
        assertTrue(rejected(chart(ChartType.BAR, new DimensionRef("date", Granularity.MONTH), null, null, COMPARISON, null, null))
                .contains("comparison"));
        assertDoesNotThrow(() -> validator.validate("transactions",
                chart(ChartType.BAR, new DimensionRef("date", Granularity.MONTH), null, null, null, null, null)));
    }

    @Test
    void rejectsAFieldNotAllowedAsTheDimension() {
        assertTrue(rejected(chart(ChartType.BREAKDOWN, new DimensionRef("amount", null), null, null, null, null, null))
                .contains("not a dimension"));
    }

    @Test
    void oldDefinitionsWithoutTheNewFieldsStillParseAndRoundTripWithoutThem() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChartDefinition old = mapper.readValue("{\"chartType\":\"bar\",\"dimension\":{\"field\":\"category\"},"
                + "\"measure\":{\"field\":\"amount\",\"aggregation\":\"sum\"},\"filters\":[]}", ChartDefinition.class);
        assertNull(old.limit());
        assertNull(old.comparison());
        assertTrue(!mapper.writeValueAsString(old).contains("subLimit"));

        ChartDefinition shaped = mapper.readValue("{\"chartType\":\"breakdown\",\"dimension\":{\"field\":\"category\"},"
                + "\"measure\":{\"field\":\"amount\",\"aggregation\":\"sum\"},\"limit\":8,\"other\":true,"
                + "\"order\":\"value_asc\",\"subDimension\":{\"field\":\"account\"},\"subLimit\":4,"
                + "\"comparison\":{\"enabled\":true,\"period\":\"previous_period\"}}", ChartDefinition.class);
        assertEquals(ChartType.BREAKDOWN, shaped.chartType());
        assertEquals(ChartOrder.VALUE_ASC, shaped.order());
        assertEquals(8, shaped.limit());
        assertEquals("account", shaped.subDimension().field());
        assertEquals(4, shaped.subLimit());
        assertTrue(mapper.writeValueAsString(shaped).contains("\"breakdown\""));
    }
}
