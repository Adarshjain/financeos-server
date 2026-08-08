package com.financeos.domain.report;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.DividendsDatasource;
import com.financeos.domain.report.datasource.impl.FnoTradesDatasource;
import com.financeos.domain.report.datasource.impl.InvestmentTradesDatasource;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.AggregatedTableDefinition;
import com.financeos.domain.report.definition.ChartDefinition;
import com.financeos.domain.report.definition.ChartType;
import com.financeos.domain.report.definition.DimensionRef;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.Granularity;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.definition.MeasureRef;
import com.financeos.domain.report.definition.RawTableDefinition;
import com.financeos.domain.report.definition.SortClause;
import com.financeos.domain.report.definition.SortDirection;
import com.financeos.domain.report.definition.TableMode;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportDefinitionValidatorTest {

    private final DatasourceCatalog catalog = new DatasourceCatalog();
    private final DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
    private final SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
    private final DatasourceRegistry registry = new DatasourceRegistry(List.of(
            new TransactionsDatasource(sqlPredicates, dateRangeResolver),
            new InvestmentTradesDatasource(sqlPredicates, dateRangeResolver),
            new DividendsDatasource(sqlPredicates, dateRangeResolver),
            new FnoTradesDatasource(sqlPredicates, dateRangeResolver)
    ), catalog);
    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(registry);

    @Test
    void validKpiPasses() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, List.of(
                new FilterClause("type", "is", TextNode.valueOf("DEBIT")),
                new FilterClause("isExcluded", "is", BooleanNode.FALSE),
                new FilterClause("date", "this_month", null)), null);
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void kpiMeasureMustBeAMeasureField() {
        KpiDefinition def = new KpiDefinition("date", Aggregation.SUM, List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void invalidOperatorForFieldTypeFails() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("amount", "contains", TextNode.valueOf("x"))), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void staticEnumMembershipIsEnforced() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("type", "is", TextNode.valueOf("FOO"))), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void isExcludedIsFilterable() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("isExcluded", "is", BooleanNode.TRUE)), null);
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void chartDateDimensionRequiresGranularity() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR,
                new DimensionRef("date", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of());
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validate("transactions", def));
        assertTrue(ex.getMessage().toLowerCase().contains("granularity"));
    }

    @Test
    void validChartWithSeriesPasses() {
        ChartDefinition def = new ChartDefinition(ChartType.BAR,
                new DimensionRef("date", Granularity.MONTH),
                new DimensionRef("category", null),
                new MeasureRef("amount", Aggregation.SUM), List.of());
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void validPivotPasses() {
        AggregatedTableDefinition def = new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("date", Granularity.MONTH)),
                List.of(new DimensionRef("category", null)),
                List.of(new MeasureRef("amount", Aggregation.SUM)),
                List.of(), List.of(new SortClause("date", SortDirection.ASC)));
        assertDoesNotThrow(() -> validator.validate("transactions", def));
    }

    @Test
    void pivotRowAndColumnOverlapFails() {
        AggregatedTableDefinition def = new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("category", null)),
                List.of(new DimensionRef("category", null)),
                List.of(new MeasureRef("amount", Aggregation.SUM)),
                List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void rawTableRequiresColumns() {
        RawTableDefinition def = new RawTableDefinition(TableMode.RAW, List.of(), List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    @Test
    void rawTableRejectsMeasureSortKey() {
        RawTableDefinition def = new RawTableDefinition(TableMode.RAW,
                List.of("date", "amount"), List.of(),
                List.of(new SortClause("amount_sum", SortDirection.DESC)));
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
    }

    // New tests for WP5
    @Test
    void unknownDatasourceRejected() {
        KpiDefinition def = new KpiDefinition("amount", Aggregation.SUM, List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("unknown_ds", def));
    }

    @Test
    void fieldScopedToDatasource() {
        // amount is valid on transactions but invalid on investment_trades
        KpiDefinition def1 = new KpiDefinition("amount", Aggregation.SUM, List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("investment_trades", def1));

        // tradeValue is valid on investment_trades but invalid on transactions
        KpiDefinition def2 = new KpiDefinition("tradeValue", Aggregation.SUM, List.of(), null);
        assertDoesNotThrow(() -> validator.validate("investment_trades", def2));
        assertThrows(ValidationException.class, () -> validator.validate("transactions", def2));
    }

    @Test
    void aggregationRulesEnforcedPerField() {
        // price on investment_trades rejections: SUM rejected, AVG accepted
        KpiDefinition sumPrice = new KpiDefinition("price", Aggregation.SUM, List.of(), null);
        assertThrows(ValidationException.class, () -> validator.validate("investment_trades", sumPrice));

        RawTableDefinition avgPriceTable = new RawTableDefinition(TableMode.RAW,
                List.of("price"), List.of(), null);
        assertDoesNotThrow(() -> validator.validate("investment_trades", avgPriceTable));
    }
}
