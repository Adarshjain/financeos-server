package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.obligations.ObligationsService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.ObligationsDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.TableMode;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import java.util.List;
import org.junit.jupiter.api.Test;

/** next_x_days is a parameterised date preset; "spend" is a transactions measure. */
class ReportDefinitionValidatorNextXDaysTest {

    private final DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
    private final SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
    private final DatasourceRegistry registry = new DatasourceRegistry(List.of(
            new TransactionsDatasource(sqlPredicates, dateRangeResolver),
            new ObligationsDatasource(mock(ObligationsService.class))
    ), new DatasourceCatalog());
    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(registry);

    @Test
    void nextXDaysWithPositiveAmountIsAcceptedOnTransactionDate() {
        assertDoesNotThrow(() -> validator.validate("transactions", kpiWithDate(amount(14))));
    }

    @Test
    void nextXDaysIsAcceptedOnTheObligationsDueDate() {
        RawTableDefinition def = new RawTableDefinition(TableMode.RAW,
                List.of("dueDate", "title", "amount", "status"),
                List.of(new FilterClause("dueDate", "next_x_days", amount(14))), null);
        assertDoesNotThrow(() -> validator.validate("obligations", def));
    }

    @Test
    void nextXDaysWithoutAValueIsRejected() {
        assertRejected(null);
    }

    @Test
    void nextXDaysWithoutAmountIsRejected() {
        assertRejected(JsonNodeFactory.instance.objectNode());
    }

    @Test
    void nextXDaysWithZeroIsRejected() {
        assertRejected(amount(0));
    }

    @Test
    void nextXDaysWithNegativeIsRejected() {
        assertRejected(amount(-1));
    }

    @Test
    void nextXDaysWithFractionIsRejected() {
        assertRejected(JsonNodeFactory.instance.objectNode().put("amount", 2.5));
    }

    @Test
    void nextXDaysWithTextAmountIsRejected() {
        assertRejected(JsonNodeFactory.instance.objectNode().put("amount", "14"));
    }

    @Test
    void nextXDaysWithBareNumberIsRejected() {
        assertRejected(JsonNodeFactory.instance.numberNode(14));
    }

    @Test
    void nextXDaysOnANonDateFieldIsRejected() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("description", "next_x_days", amount(7))), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void spendIsAValidKpiMeasure() {
        KpiDefinition def = new KpiDefinition("spend", Aggregation.SUM,
                List.of(new FilterClause("date", "this_month", null)), null);
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void theSeededSpendThisMonthChartValidates() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR,
                new DimensionRef("category", null), null,
                new MeasureRef("spend", Aggregation.SUM),
                List.of(new FilterClause("date", "this_month", null),
                        new FilterClause("type", "is", TextNode.valueOf("DEBIT")),
                        new FilterClause("isExcluded", "is", BooleanNode.FALSE),
                        new FilterClause("isTransferLeg", "is", BooleanNode.FALSE)));
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void spendIsNotADimension() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR,
                new DimensionRef("spend", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of());
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    private void assertRejected(JsonNode value) {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate("transactions", kpiWithDate(value)));
        assertTrue(ex.getMessage().contains("next_x_days"));
    }

    private static KpiDefinition kpiWithDate(JsonNode value) {
        return new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("date", "next_x_days", value)), null);
    }

    private static JsonNode amount(int n) {
        return JsonNodeFactory.instance.objectNode().put("amount", n);
    }
}
