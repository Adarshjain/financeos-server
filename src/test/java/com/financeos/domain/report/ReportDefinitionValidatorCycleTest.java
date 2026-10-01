package com.financeos.domain.report;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.DividendsDatasource;
import com.financeos.domain.report.datasource.impl.RewardEarningsDatasource;
import com.financeos.domain.report.datasource.impl.RewardReportSupport;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.report.definition.KpiDefinition;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.reward.RewardCalculationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReportDefinitionValidatorCycleTest {

    private final DatasourceCatalog catalog = new DatasourceCatalog();
    private final DateRangeResolver resolver = new DateRangeResolver(4);
    private final SqlPredicates predicates = new SqlPredicates(resolver);
    private final DatasourceRegistry registry = new DatasourceRegistry(List.of(
            new TransactionsDatasource(predicates, resolver),
            new DividendsDatasource(predicates, resolver),
            new RewardEarningsDatasource(mock(RewardCalculationService.class), mock(RewardReportSupport.class))), catalog);
    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(registry);

    private static KpiDefinition kpi(String measure, FilterClause f) {
        return new KpiDefinition(measure, Aggregation.SUM, List.of(f), null);
    }

    private static FilterClause op(String field, String operator) {
        return new FilterClause(field, operator, null);
    }

    @Test
    void cycleOperatorsAcceptedOnTransactionDateFields() {
        for (String field : List.of("date", "settlementDate")) {
            for (String o : List.of("this_billing_cycle", "previous_billing_cycle")) {
                assertDoesNotThrow(() -> validator.validate("transactions", kpi("amount", op(field, o))), field + " " + o);
            }
        }
    }

    @Test
    void cycleOperatorsAcceptedOnRewardEarningsDateFields() {
        for (String field : List.of("effectiveDate", "transactionDate")) {
            assertDoesNotThrow(() -> validator.validate("reward_earnings", kpi("valueInr", op(field, "previous_billing_cycle"))), field);
        }
    }

    @Test
    void cycleOperatorRejectedOnNonCycleDatasourceWithClearMessage() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> validator.validate("dividends", kpi("amount", op("payDate", "this_billing_cycle"))));
        assertTrue(e.getMessage().contains("only available on billing-cycle date fields"), e.getMessage());
        assertTrue(e.getMessage().contains("this_billing_cycle"));
    }

    @Test
    void cycleOperatorRejectedOnNonDateField() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> validator.validate("transactions", kpi("amount", op("category", "this_billing_cycle"))));
        assertTrue(e.getMessage().contains("only available on billing-cycle date fields"), e.getMessage());
    }

    @Test
    void cycleOperatorsRejectAValue() {
        FilterClause withValue = new FilterClause("date", "this_billing_cycle", TextNode.valueOf("x"));
        ValidationException e = assertThrows(ValidationException.class,
                () -> validator.validate("transactions", kpi("amount", withValue)));
        assertTrue(e.getMessage().contains("does not take a value"));
    }

    @Test
    void jsonNullValueIsTreatedAsValueless() {
        FilterClause nullValue = new FilterClause("date", "previous_billing_cycle", JsonNodeFactory.instance.nullNode());
        assertDoesNotThrow(() -> validator.validate("transactions", kpi("amount", nullValue)));
    }

    @Test
    void internalCyclesAgoOperatorIsNotAcceptedFromUsers() {
        FilterClause ago = new FilterClause("date", "billing_cycles_ago", JsonNodeFactory.instance.objectNode().put("amount", 1));
        assertThrows(ValidationException.class, () -> validator.validate("transactions", kpi("amount", ago)));
    }

    @Test
    void existingDateOperatorsStillValidate() {
        assertDoesNotThrow(() -> validator.validate("transactions", kpi("amount", op("date", "this_month"))));
        assertThrows(ValidationException.class, () -> validator.validate("transactions", kpi("amount", op("date", "nonsense"))));
    }
}
