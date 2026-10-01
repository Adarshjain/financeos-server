package com.financeos.domain.report.datasource;

import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.impl.RewardEarningsDatasource;
import com.financeos.domain.report.datasource.impl.RewardReportSupport;
import com.financeos.domain.reward.RewardCalculationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DatasourceCatalogCycleTest {

    private final DatasourceCatalog catalog = new DatasourceCatalog();

    @Test
    void dateOperatorsExposeTheCycleList() {
        assertEquals(List.of("this_billing_cycle", "previous_billing_cycle"), DatasourceCatalog.OPERATORS.date().cycle());
    }

    @Test
    void cycleOperatorsAreNotInTheAbsoluteOrRelativeLists() {
        var date = DatasourceCatalog.OPERATORS.date();
        assertFalse(date.absolute().contains("this_billing_cycle"));
        assertFalse(date.relative().contains("this_billing_cycle"));
        assertFalse(date.relative().contains("previous_billing_cycle"));
    }

    @Test
    void operatorsForDateDeliberatelyExcludeCycleOperators() {
        Set<String> ops = catalog.operatorsFor(FieldType.DATE);
        assertFalse(ops.contains("this_billing_cycle"));
        assertFalse(ops.contains("previous_billing_cycle"));
        assertFalse(ops.contains("billing_cycles_ago"));
        assertTrue(ops.contains("this_month"));
        assertTrue(ops.contains("between"));
    }

    @Test
    void onlyTransactionDateAndSettlementDateAreCycleFields() {
        Set<String> cycleFields = DatasourceCatalog.transactionFields().stream()
                .filter(f -> Boolean.TRUE.equals(f.billingCycle()))
                .map(FieldDef::name).collect(Collectors.toSet());
        assertEquals(Set.of("date", "settlementDate"), cycleFields);
        assertTrue(DatasourceCatalog.transactionFields().stream()
                .filter(f -> f.type() == FieldType.DATE)
                .allMatch(f -> Boolean.TRUE.equals(f.billingCycle())));
    }

    @Test
    void transactionsHasABillingCycleStringDimension() {
        FieldDef f = catalog.field("billingCycle");
        assertNotNull(f);
        assertEquals("Billing cycle", f.label());
        assertEquals(FieldType.STRING, f.type());
        assertEquals(FieldRole.DIMENSION, f.role());
        assertNull(f.billingCycle());
    }

    @Test
    void rewardEarningsCycleFieldsAndLabel() {
        RewardEarningsDatasource ds = new RewardEarningsDatasource(mock(RewardCalculationService.class), mock(RewardReportSupport.class));

        assertTrue(ds.field("effectiveDate").billingCycle());
        assertTrue(ds.field("transactionDate").billingCycle());
        assertEquals("Billing cycle", ds.field("cycle").label());
        assertNull(ds.field("cycle").billingCycle());
        assertEquals("cardId", ds.cycleAccountKey());
    }

    @Test
    void cycleDateFactoryBuildsAFlaggedDateDimension() {
        FieldDef f = FieldDef.cycleDate("d", "D", List.of());
        assertEquals(FieldType.DATE, f.type());
        assertEquals(FieldRole.DIMENSION, f.role());
        assertTrue(f.billingCycle());
    }

    @Test
    void legacyFieldDefConstructorsLeaveBillingCycleNull() {
        FieldDef eight = new FieldDef("a", "A", FieldType.DATE, FieldRole.DIMENSION, null, null, null, List.of());
        FieldDef nine = new FieldDef("a", "A", FieldType.DATE, FieldRole.DIMENSION, null, null, null, List.of(), "x");
        FieldDef ten = new FieldDef("a", "A", FieldType.DATE, FieldRole.DIMENSION, null, null, null, List.of(), "x", "id");
        assertNull(eight.billingCycle());
        assertNull(nine.billingCycle());
        assertNull(ten.billingCycle());
        assertEquals("id", ten.idField());
    }

    @Test
    void computedDatasourcesHaveNoCycleKeyByDefault() {
        ComputedReportDatasource ds = new ComputedReportDatasource() {
            public String name() { return "x"; }
            public String label() { return "x"; }
            public List<FieldDef> fields() { return List.of(); }
            public List<java.util.Map<String, Object>> rows() { return List.of(); }
        };
        assertNull(ds.cycleAccountKey());
    }
}
