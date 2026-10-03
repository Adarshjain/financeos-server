package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.ComputedReportDatasource;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.impl.RewardEarningsDatasource;
import com.financeos.domain.report.datasource.impl.RewardReportSupport;
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
import com.financeos.domain.report.definition.ReportDefinition;
import com.financeos.domain.report.definition.TableMode;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.reward.RewardCalculationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The single-account rule for billing-cycle filters and groupings. */
class ReportDefinitionValidatorBillingCycleScopeTest {

    private final DatasourceCatalog catalog = new DatasourceCatalog();
    private final DateRangeResolver resolver = new DateRangeResolver(4);
    private final SqlPredicates predicates = new SqlPredicates(resolver);

    /** Has a billing-cycle grouping field but no account field to scope it by. */
    private static class NoAccountFieldDatasource implements ComputedReportDatasource {
        @Override public String name() { return "no_account"; }
        @Override public String label() { return "No account"; }
        @Override public List<FieldDef> fields() {
            return List.of(
                    FieldDef.cycleGrouping("cycle", "Billing cycle", List.of(ReportType.CHART, ReportType.TABLE)),
                    new FieldDef("amount", "Amount", FieldType.NUMBER, FieldRole.MEASURE,
                            List.of(Aggregation.SUM), null, null, List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE)));
        }
        @Override public List<Map<String, Object>> rows() { return List.of(); }
    }

    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(new DatasourceRegistry(List.of(
            new TransactionsDatasource(predicates, resolver),
            new RewardEarningsDatasource(mock(RewardCalculationService.class), mock(RewardReportSupport.class)),
            new NoAccountFieldDatasource()), catalog));

    // ---- builders ----

    private static FilterClause is(String field, String value) {
        return new FilterClause(field, "is", TextNode.valueOf(value));
    }

    private static FilterClause in(String field, String... values) {
        ArrayNode a = JsonNodeFactory.instance.arrayNode();
        for (String v : values) a.add(v);
        return new FilterClause(field, "in", a);
    }

    private static FilterClause cycle() {
        return new FilterClause("date", "this_billing_cycle", null);
    }

    private static KpiDefinition kpi(FilterClause... f) {
        return new KpiDefinition("amount", Aggregation.SUM, List.of(f), null);
    }

    private static ChartDefinition chart(String dimension, String series, FilterClause... f) {
        return new ChartDefinition(ChartType.BAR, new DimensionRef(dimension, null),
                series == null ? null : new DimensionRef(series, null), new MeasureRef("amount", Aggregation.SUM), List.of(f));
    }

    private void expectScopeError(ReportDefinition def) {
        ValidationException e = assertThrows(ValidationException.class, () -> validator.validate("transactions", def));
        assertTrue(e.getMessage().contains("Billing cycles differ per account"), e.getMessage());
        assertTrue(e.getMessage().contains("'Account is"), e.getMessage());
    }

    // ---- cycle filters ----

    @Test
    void cycleFilterWithoutAnAccountFilterIsRejected() {
        expectScopeError(kpi(cycle()));
    }

    @Test
    void cycleFilterWithOneAccountIsFilterIsAccepted() {
        assertDoesNotThrow(() -> validator.validate("transactions", kpi(cycle(), is("account", "HDFC"))));
    }

    @Test
    void cycleFilterWithAnInFilterOfOneValueIsAccepted() {
        assertDoesNotThrow(() -> validator.validate("transactions", kpi(cycle(), in("account", "HDFC"))));
    }

    @Test
    void cycleFilterWithAnInFilterOfTwoValuesIsRejected() {
        expectScopeError(kpi(cycle(), in("account", "HDFC", "SBI")));
    }

    @Test
    void cycleFilterWithTwoAccountFiltersIsRejected() {
        expectScopeError(kpi(cycle(), is("account", "HDFC"), is("account", "SBI")));
    }

    @Test
    void aSingleValueFilterPlusAMultiValueFilterOnTheAccountIsRejected() {
        expectScopeError(kpi(cycle(), is("account", "HDFC"), in("account", "SBI", "ICICI")));
    }

    @Test
    void anIsFilterWithANullValueDoesNotScopeTheReport() {
        // the generic filter check rejects a valueless "is" before the cycle rule is reached
        assertThrows(ValidationException.class, () -> validator.validate("transactions",
                kpi(cycle(), new FilterClause("account", "is", JsonNodeFactory.instance.nullNode()))));
        assertThrows(ValidationException.class, () -> validator.validate("transactions",
                kpi(cycle(), new FilterClause("account", "is", null))));
    }

    @Test
    void anAccountFilterOnADifferentFieldDoesNotScopeTheReport() {
        expectScopeError(kpi(cycle(), is("category", "Food")));
    }

    @Test
    void theRewardEarningsDatasourceIsScopedByCard() {
        FilterClause effective = new FilterClause("effectiveDate", "previous_billing_cycle", null);
        KpiDefinition unscoped = new KpiDefinition("valueInr", Aggregation.SUM, List.of(effective), null);
        ValidationException e = assertThrows(ValidationException.class, () -> validator.validate("reward_earnings", unscoped));
        assertTrue(e.getMessage().contains("'Card is"), e.getMessage());

        assertDoesNotThrow(() -> validator.validate("reward_earnings",
                new KpiDefinition("valueInr", Aggregation.SUM, List.of(effective, is("card", "Regalia")), null)));
    }

    // ---- billing-cycle groupings ----

    @Test
    void groupingByBillingCycleAsTheChartDimensionNeedsOneAccount() {
        expectScopeError(chart("billingCycle", null));
        assertDoesNotThrow(() -> validator.validate("transactions", chart("billingCycle", null, is("account", "HDFC"))));
    }

    @Test
    void groupingByBillingCycleAsTheChartSeriesNeedsOneAccount() {
        expectScopeError(chart("category", "billingCycle"));
        assertDoesNotThrow(() -> validator.validate("transactions", chart("category", "billingCycle", is("account", "HDFC"))));
    }

    @Test
    void billingCycleAsARawTableColumnNeedsOneAccount() {
        RawTableDefinition unscoped = new RawTableDefinition(TableMode.RAW, List.of("description", "billingCycle"), List.of(), null);
        expectScopeError(unscoped);
        assertDoesNotThrow(() -> validator.validate("transactions", new RawTableDefinition(TableMode.RAW,
                List.of("description", "billingCycle"), List.of(is("account", "HDFC")), null)));
    }

    @Test
    void billingCycleAsAnAggregatedRowNeedsOneAccount() {
        AggregatedTableDefinition unscoped = new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("billingCycle", null)), List.of(), List.of(new MeasureRef("amount", Aggregation.SUM)),
                List.of(), null);
        expectScopeError(unscoped);
        assertDoesNotThrow(() -> validator.validate("transactions", new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("billingCycle", null)), List.of(), List.of(new MeasureRef("amount", Aggregation.SUM)),
                List.of(is("account", "HDFC")), null)));
    }

    @Test
    void billingCycleAsAnAggregatedColumnNeedsOneAccount() {
        AggregatedTableDefinition unscoped = new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("category", null)), List.of(new DimensionRef("billingCycle", null)),
                List.of(new MeasureRef("amount", Aggregation.SUM)), List.of(), null);
        expectScopeError(unscoped);
        assertDoesNotThrow(() -> validator.validate("transactions", new AggregatedTableDefinition(TableMode.AGGREGATED,
                List.of(new DimensionRef("category", null)), List.of(new DimensionRef("billingCycle", null)),
                List.of(new MeasureRef("amount", Aggregation.SUM)), List.of(in("account", "HDFC")), null)));
    }

    @Test
    void theRewardEarningsCycleGroupingNeedsOneCard() {
        ChartDefinition byCycle = new ChartDefinition(ChartType.BAR, new DimensionRef("cycle", null), null,
                new MeasureRef("valueInr", Aggregation.SUM), List.of());
        ValidationException e = assertThrows(ValidationException.class, () -> validator.validate("reward_earnings", byCycle));
        assertTrue(e.getMessage().contains("'Card is"), e.getMessage());
    }

    // ---- what does not need an account ----

    @Test
    void aDateDimensionFlaggedAsBillingCycleDoesNotNeedAnAccount() {
        // transactions.date carries the billing-cycle flag, but grouping by it (month buckets) is not a cycle grouping
        ChartDefinition byMonth = new ChartDefinition(ChartType.LINE, new DimensionRef("date", Granularity.MONTH), null,
                new MeasureRef("amount", Aggregation.SUM), List.of());
        assertDoesNotThrow(() -> validator.validate("transactions", byMonth));
    }

    @Test
    void reportsWithoutCyclesNeedNoAccount() {
        assertDoesNotThrow(() -> validator.validate("transactions", kpi(new FilterClause("date", "this_month", null))));
        assertDoesNotThrow(() -> validator.validate("transactions", chart("category", null)));
    }

    // ---- datasource without an account field ----

    @Test
    void cycleGroupingOnADatasourceWithoutAnAccountFieldIsNotAvailable() {
        ChartDefinition byCycle = new ChartDefinition(ChartType.BAR, new DimensionRef("cycle", null), null,
                new MeasureRef("amount", Aggregation.SUM), List.of());
        ValidationException e = assertThrows(ValidationException.class, () -> validator.validate("no_account", byCycle));
        assertEquals("Billing cycles are not available on this datasource", e.getMessage());
    }
}
