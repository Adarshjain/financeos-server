package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.CardholderRelationship;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.report.datasource.Aggregation;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.datasource.ReportDatasource;
import com.financeos.domain.report.datasource.impl.LendingsDatasource;
import com.financeos.domain.report.datasource.impl.LoanPaymentsDatasource;
import com.financeos.domain.report.datasource.impl.LoanTaxSummaryDatasource;
import com.financeos.domain.report.datasource.impl.RewardEarningsDatasource;
import com.financeos.domain.report.datasource.impl.RewardReportSupport;
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
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.TransactionChannel;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.link.LinkType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which fields reports may filter on: grouping-only fields are marked not filterable and
 * rejected as filters; static enum lists come from the real enums; cardholder and financial
 * year are dropdowns.
 */
class ReportFilterFieldsTest {

    private final DateRangeResolver dateRangeResolver = new DateRangeResolver(4);
    private final SqlPredicates sqlPredicates = new SqlPredicates(dateRangeResolver);
    private final TransactionsDatasource transactions = new TransactionsDatasource(sqlPredicates, dateRangeResolver);
    private final RewardEarningsDatasource rewardEarnings =
            new RewardEarningsDatasource(mock(RewardCalculationService.class), mock(RewardReportSupport.class));
    private final LendingsDatasource lendings = new LendingsDatasource(mock(LendingService.class));
    private final LoanPaymentsDatasource loanPayments = new LoanPaymentsDatasource(mock(LoanService.class));
    private final LoanTaxSummaryDatasource loanTaxSummary =
            new LoanTaxSummaryDatasource(mock(LoanService.class), dateRangeResolver);
    private final ReportDefinitionValidator validator = new ReportDefinitionValidator(new DatasourceRegistry(
            List.of(transactions, rewardEarnings, lendings, loanPayments, loanTaxSummary), new DatasourceCatalog()));

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static List<String> notFilterable(ReportDatasource ds) {
        return ds.fields().stream().filter(f -> !f.canFilter()).map(FieldDef::name).toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void groupingOnlyFieldsAreExactlyTheLabelsIdsAndCounter() {
        assertEquals(List.of("billingCycle"), notFilterable(transactions));
        assertEquals(List.of("cycle", "rewardYear", "txnCount"), notFilterable(rewardEarnings));
        assertEquals(List.of("counterpartyId", "transactionId"), notFilterable(lendings));
        assertEquals(List.of("loanId"), notFilterable(loanPayments));
        assertEquals(List.of("loanId"), notFilterable(loanTaxSummary));
    }

    @Test
    void transactionsStaticEnumsListTheStoredEnumValues() {
        assertEquals(names(TransactionSource.values()), transactions.field("source").values());
        assertEquals(List.of("gmail_transaction_alert", "gmail_statement", "manual", "file_upload"),
                transactions.field("source").values());
        assertEquals(names(TransactionChannel.values()), transactions.field("channel").values());
        assertTrue(transactions.field("channel").values().contains("ATM"));
        assertEquals(names(AccountType.values()), transactions.field("accountType").values());
        assertEquals(names(LinkType.values()), transactions.field("linkType").values());
        assertEquals(names(ReviewType.values()), transactions.field("reviewType").values());
        assertEquals(names(CardholderRelationship.values()), transactions.field("cardRelationship").values());
    }

    @Test
    void cardholderAndFinancialYearAreDropdownsOfUserValues() {
        FieldDef cardholder = transactions.field("cardholder");
        assertEquals(FieldType.ENUM, cardholder.type());
        assertEquals(Boolean.TRUE, cardholder.dynamic());
        assertNull(cardholder.values());
        FieldDef fy = loanTaxSummary.field("financialYear");
        assertEquals(FieldType.ENUM, fy.type());
        assertEquals(Boolean.TRUE, fy.dynamic());
    }

    @Test
    void earnedLabelSaysItMixesUnits() {
        assertEquals("Earned (₹ or points)", rewardEarnings.field("earned").label());
    }

    // ------------------------------------------------------------------ FieldDef

    @Test
    void notFilterableKeepsEveryOtherComponentAndOnlyFlipsFilterable() {
        FieldDef base = new FieldDef("x", "X", FieldType.STRING, FieldRole.DIMENSION, null, List.of("a"), true,
                List.of(ReportType.TABLE), "number", "xId", true);
        FieldDef pruned = base.notFilterable();
        assertTrue(base.canFilter());
        assertNull(base.filterable());
        assertFalse(pruned.canFilter());
        assertEquals(Boolean.FALSE, pruned.filterable());
        assertEquals(new FieldDef("x", "X", FieldType.STRING, FieldRole.DIMENSION, null, List.of("a"), true,
                List.of(ReportType.TABLE), "number", "xId", true, false), pruned);
    }

    @Test
    void jsonCarriesFilterableFalseAndOmitsItOtherwise() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode pruned = mapper.valueToTree(rewardEarnings.field("rewardYear"));
        JsonNode normal = mapper.valueToTree(rewardEarnings.field("mcc"));
        assertTrue(pruned.has("filterable"));
        assertFalse(pruned.get("filterable").asBoolean());
        assertFalse(normal.has("filterable"));
        assertFalse(normal.has("canFilter"));
    }

    // ------------------------------------------------------------------ validator

    private void assertFilterRejected(String datasource, String measure, FilterClause filter, String label) {
        KpiDefinition def = new KpiDefinition(measure, Aggregation.SUM, List.of(filter), null);
        ValidationException e = assertThrows(ValidationException.class, () -> validator.validate(datasource, def));
        assertEquals("'" + label + "' can't be used as a filter; group by it or show it as a column instead", e.getMessage());
    }

    @Test
    void everyGroupingOnlyFieldIsRejectedAsAFilter() {
        assertFilterRejected("transactions", "amount",
                new FilterClause("billingCycle", "contains", TextNode.valueOf("2026")), "Billing cycle");
        assertFilterRejected("reward_earnings", "valueInr",
                new FilterClause("cycle", "contains", TextNode.valueOf("2026")), "Billing cycle");
        assertFilterRejected("reward_earnings", "valueInr",
                new FilterClause("rewardYear", "contains", TextNode.valueOf("2026")), "Reward year");
        assertFilterRejected("reward_earnings", "valueInr",
                new FilterClause("txnCount", "equals", IntNode.valueOf(1)), "Eligible transactions");
        assertFilterRejected("lendings", "amount",
                new FilterClause("counterpartyId", "exact", TextNode.valueOf("x")), "Counterparty ID");
        assertFilterRejected("lendings", "amount",
                new FilterClause("transactionId", "exact", TextNode.valueOf("x")), "Transaction ID");
        assertFilterRejected("loan_payments", "paidAmount",
                new FilterClause("loanId", "exact", TextNode.valueOf("x")), "Loan ID");
        assertFilterRejected("loan_tax_summary", "interestPaid",
                new FilterClause("loanId", "exact", TextNode.valueOf("x")), "Loan ID");
    }

    @Test
    void groupingOnlyFieldsStillWorkAsGroupingsAndColumns() {
        ChartDefinition byRewardYear = new ChartDefinition(ChartType.BAR, new DimensionRef("rewardYear", null), null,
                new MeasureRef("valueInr", Aggregation.SUM), List.of());
        assertDoesNotThrow(() -> validator.validate("reward_earnings", byRewardYear));
        RawTableDefinition withIds = new RawTableDefinition(TableMode.RAW,
                List.of("entryDate", "counterpartyId", "transactionId"), List.of(), List.of());
        assertDoesNotThrow(() -> validator.validate("lendings", withIds));
        RawTableDefinition withLoanId = new RawTableDefinition(TableMode.RAW, List.of("paymentDate", "loanId"), List.of(), List.of());
        assertDoesNotThrow(() -> validator.validate("loan_payments", withLoanId));
    }

    @Test
    void fixedEnumValuesAndDropdownFieldsAreAcceptedAsFilters() {
        for (String value : List.of("gmail_transaction_alert", "gmail_statement", "manual", "file_upload")) {
            assertDoesNotThrow(() -> validator.validate("transactions", new KpiDefinition("amount", Aggregation.SUM,
                    List.of(new FilterClause("source", "is", TextNode.valueOf(value))), null)));
        }
        assertDoesNotThrow(() -> validator.validate("transactions", new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("channel", "is", TextNode.valueOf("ATM"))), null)));
        assertDoesNotThrow(() -> validator.validate("transactions", new KpiDefinition("amount", Aggregation.SUM,
                List.of(new FilterClause("cardholder", "is", TextNode.valueOf("Priya"))), null)));
        assertDoesNotThrow(() -> validator.validate("loan_tax_summary", new KpiDefinition("interestPaid", Aggregation.SUM,
                List.of(new FilterClause("financialYear", "is", TextNode.valueOf("FY 25-26"))), null)));
        // A string operator no longer applies to the cardholder dropdown.
        assertThrows(ValidationException.class, () -> validator.validate("transactions", new KpiDefinition("amount",
                Aggregation.SUM, List.of(new FilterClause("cardholder", "contains", TextNode.valueOf("Pri"))), null)));
    }

    // ------------------------------------------------------------------ filter values

    @Test
    void transactionsValuesIncludeTheCardholderDropdown() {
        UserContext.setCurrentUserId(UUID.randomUUID());
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of("Priya", "Unattributed"));
        ReportFieldValuesService service = new ReportFieldValuesService(
                new DatasourceRegistry(List.of(transactions), new DatasourceCatalog()));
        ReflectionTestUtils.setField(service, "em", em);

        Map<String, List<String>> values = service.values("transactions").values();

        assertEquals(List.of("category", "account", "card", "cardholder"), List.copyOf(values.keySet()));
        assertEquals(List.of("Priya", "Unattributed"), values.get("cardholder"));
        verify(em).createNativeQuery(org.mockito.ArgumentMatchers.contains("NVL(ch.person_name, 'Unattributed')"));
    }
}
