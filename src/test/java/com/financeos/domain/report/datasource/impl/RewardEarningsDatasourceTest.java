package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.reward.dto.RewardLineResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.report.datasource.ComputedReportDatasource.DateHint;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.reward.AccrualType;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardCalculationService.ReportLine;
import com.financeos.domain.reward.RewardLineReason;
import com.financeos.domain.reward.RuleStacking;
import com.financeos.domain.transaction.TransactionChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class RewardEarningsDatasourceTest {

    private static final LocalDate CYCLE_START = LocalDate.of(2025, 1, 1);
    private static final LocalDate CYCLE_END = LocalDate.of(2025, 1, 31);
    private static final LocalDate YEAR_START = LocalDate.of(2024, 6, 1);
    private static final LocalDate YEAR_END = LocalDate.of(2025, 5, 31);

    private RewardCalculationService rewardCalculationService;
    private RewardReportSupport support;
    private RewardEarningsDatasource datasource;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        rewardCalculationService = mock(RewardCalculationService.class);
        support = mock(RewardReportSupport.class);
        datasource = new RewardEarningsDatasource(rewardCalculationService, support);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Account account(String name, String pointValue) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setName(name);
        a.setPointValueInr(pointValue == null ? null : new BigDecimal(pointValue));
        return a;
    }

    private static RewardLineResponse line(UUID txn, UUID ruleId, String ruleName, String earned, String unit,
                                           RewardLineReason reason, String cardLabel) {
        return new RewardLineResponse(
                txn, LocalDate.of(2025, 1, 15), LocalDate.of(2025, 1, 16),
                "Flight Booking", "AIR INDIA", "3000", TransactionChannel.ONLINE,
                new BigDecimal("10000.00"), new BigDecimal("9000.00"),
                ruleId, ruleName, ruleId == null ? null : RuleStacking.EXCLUSIVE,
                ruleId == null ? null : AccrualType.PERCENT,
                new BigDecimal(earned), unit, reason, null, cardLabel);
    }

    private static ReportLine reportLine(RewardLineResponse l, boolean primary, boolean eligible, String spend,
                                         String discount, String fee, List<String> categories) {
        return new ReportLine(l, primary, eligible, new BigDecimal(spend),
                discount == null ? null : new BigDecimal(discount),
                fee == null ? null : new BigDecimal(fee),
                categories, CYCLE_START, CYCLE_END, YEAR_START, YEAR_END);
    }

    private void stub(Account account, Map<UUID, String> labels, List<ReportLine> lines) {
        when(support.ruleAccounts(userId)).thenReturn(List.of(account));
        when(support.ruleLabels(List.of(account))).thenReturn(labels);
        when(support.bounds(account.getId())).thenReturn(
                new RewardReportSupport.DateBounds(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31)));
        when(rewardCalculationService.reportLines(eq(account.getId()), any(), any())).thenReturn(lines);
    }

    @Test
    void catalogShapeAndName() {
        assertEquals("reward_earnings", datasource.name());
        assertEquals("Reward Earnings", datasource.label());
        List<String> names = datasource.fields().stream().map(FieldDef::name).toList();
        for (String expected : List.of("effectiveDate", "transactionDate", "card", "cardholder", "rule", "category",
                "reason", "earnedUnit", "stacking", "accrualType", "channel", "mcc", "cycle", "rewardYear",
                "description", "valueInr", "cashInr", "points", "pointsValueInr", "netValueInr", "earned", "spend",
                "amount", "basis", "instantDiscount", "convenienceFee", "txnCount")) {
            assertTrue(names.contains(expected), "catalog should contain " + expected);
        }
        assertEquals(names.size(), new HashSet<>(names).size(), "catalog field names are unique");
    }

    @Test
    void rowsWithoutUserAreEmpty() {
        UserContext.clear();
        assertTrue(datasource.rows().isEmpty());
        verifyNoInteractions(support, rewardCalculationService);
    }

    @Test
    void rowsWithNoRuleCardsAreEmpty() {
        when(support.ruleAccounts(userId)).thenReturn(List.of());
        assertTrue(datasource.rows().isEmpty());
        verifyNoInteractions(rewardCalculationService);
    }

    @Test
    void rowsEvaluationWithValuedPointsRupeesUnvaluedPointsAndNoRule() {
        Account infinia = account("Infinia Card", "0.25");
        Account generic = account("Generic Points Card", null);
        Account unused = account("Unused Card", null);
        UUID ruleA1 = UUID.randomUUID();
        UUID ruleA2 = UUID.randomUUID();
        UUID ruleB1 = UUID.randomUUID();
        UUID tA1 = UUID.randomUUID(), tA2 = UUID.randomUUID(), tB1 = UUID.randomUUID(), tB2 = UUID.randomUUID();

        when(support.ruleAccounts(userId)).thenReturn(List.of(infinia, generic, unused));
        when(support.ruleLabels(List.of(infinia, generic, unused)))
                .thenReturn(Map.of(ruleA1, "SmartBuy 5X", ruleA2, "Grocery 1%", ruleB1, "Base Points"));
        var bounds = new RewardReportSupport.DateBounds(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31));
        when(support.bounds(infinia.getId())).thenReturn(bounds);
        when(support.bounds(generic.getId())).thenReturn(bounds);
        when(support.bounds(unused.getId())).thenReturn(null); // no transactions -> skipped

        when(rewardCalculationService.reportLines(infinia.getId(), bounds.from(), bounds.to())).thenReturn(List.of(
                reportLine(line(tA1, ruleA1, "SmartBuy 5X", "400", "POINTS", RewardLineReason.MATCHED, null), true, true, "9000", null, null, List.of()),
                reportLine(line(tA2, ruleA2, "Grocery 1%", "50.00", "RUPEES", RewardLineReason.MATCHED, null), true, true, "5000", null, null, List.of())));
        when(rewardCalculationService.reportLines(generic.getId(), bounds.from(), bounds.to())).thenReturn(List.of(
                reportLine(line(tB1, ruleB1, "Base Points", "100", "POINTS", RewardLineReason.MATCHED, null), true, true, "2000", null, null, List.of()),
                reportLine(line(tB2, null, null, "0", "RUPEES", RewardLineReason.NO_RULE, null), true, true, "1000", null, null, List.of())));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(4, rows.size());

        Map<String, Object> r1 = rows.get(0);
        assertEquals(tA1 + "_0", r1.get("id"));
        assertEquals("Infinia Card", r1.get("card"));
        assertEquals("SmartBuy 5X", r1.get("rule"));
        assertEquals("MATCHED", r1.get("reason"));
        assertEquals("POINTS", r1.get("earnedUnit"));
        assertEquals("EXCLUSIVE", r1.get("stacking"));
        assertEquals("PERCENT", r1.get("accrualType"));
        assertEquals("ONLINE", r1.get("channel"));
        assertEquals("3000", r1.get("mcc"));
        assertEquals("Flight Booking", r1.get("description"));
        assertEquals(LocalDate.of(2025, 1, 16), r1.get("effectiveDate"));
        assertEquals(LocalDate.of(2025, 1, 15), r1.get("transactionDate"));
        assertEquals(new BigDecimal("400"), r1.get("earned"));
        assertEquals(new BigDecimal("100.00"), r1.get("valueInr"));       // 400 pts x 0.25
        assertEquals(new BigDecimal("0.00"), r1.get("cashInr"));
        assertEquals(new BigDecimal("400"), r1.get("points"));
        assertEquals(new BigDecimal("100.00"), r1.get("pointsValueInr"));
        assertEquals(new BigDecimal("9000.00"), r1.get("basis"));

        Map<String, Object> r2 = rows.get(1);
        assertEquals(tA2 + "_1", r2.get("id"));
        assertEquals("RUPEES", r2.get("earnedUnit"));
        assertEquals(new BigDecimal("50.00"), r2.get("valueInr"));
        assertEquals(new BigDecimal("50.00"), r2.get("cashInr"));
        assertEquals(BigDecimal.ZERO, r2.get("points"));
        assertEquals(new BigDecimal("0.00"), r2.get("pointsValueInr"));

        Map<String, Object> r3 = rows.get(2);
        assertEquals(tB1 + "_2", r3.get("id"));
        assertEquals("Generic Points Card", r3.get("card"));
        assertEquals("POINTS", r3.get("earnedUnit"));
        assertEquals(new BigDecimal("100"), r3.get("earned"));
        assertEquals(new BigDecimal("100"), r3.get("points"));      // unvalued points stay visible
        assertEquals(new BigDecimal("0.00"), r3.get("valueInr"));   // ...but are worth 0 rupees
        assertEquals(new BigDecimal("0.00"), r3.get("pointsValueInr"));

        Map<String, Object> r4 = rows.get(3);
        assertEquals(tB2 + "_3", r4.get("id"));
        assertEquals("(none)", r4.get("rule"));
        assertEquals("NO_RULE", r4.get("reason"));
        assertNull(r4.get("stacking"));
        assertNull(r4.get("accrualType"));
        assertEquals(new BigDecimal("0"), r4.get("earned"));
        assertEquals(new BigDecimal("0.00"), r4.get("valueInr"));

        Set<String> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            assertTrue(ids.add((String) row.get("id")), "Row IDs must be unique");
        }
        for (FieldDef fieldDef : datasource.fields()) {
            for (Map<String, Object> row : rows) {
                assertTrue(row.containsKey(fieldDef.name()), "Row should contain catalog field key: " + fieldDef.name());
            }
        }
        verify(rewardCalculationService, never()).reportLines(eq(unused.getId()), any(), any());
    }

    @Test
    void perTransactionMeasuresCarriedOnceOnATwoLineTransaction() {
        Account card = account("Card", "0.25");
        UUID txn = UUID.randomUUID();
        UUID excl = UUID.randomUUID(), add = UUID.randomUUID();
        RewardLineResponse first = line(txn, excl, "Base", "90.00", "RUPEES", RewardLineReason.MATCHED, null);
        RewardLineResponse second = line(txn, add, "Bonus", "40", "POINTS", RewardLineReason.MATCHED, null);
        stub(card, Map.of(excl, "Base", add, "Bonus"), List.of(
                reportLine(first, true, true, "9000", "25.00", "10.00", List.of()),
                reportLine(second, false, true, "9000", "25.00", "10.00", List.of())));

        List<Map<String, Object>> rows = datasource.rows();
        Map<String, Object> primary = rows.get(0);
        Map<String, Object> other = rows.get(1);

        assertEquals(new BigDecimal("9000"), primary.get("spend"));
        assertEquals(new BigDecimal("10000.00"), primary.get("amount"));
        assertEquals(new BigDecimal("25.00"), primary.get("instantDiscount"));
        assertEquals(new BigDecimal("10.00"), primary.get("convenienceFee"));
        assertEquals(BigDecimal.ONE, primary.get("txnCount"));

        assertEquals(BigDecimal.ZERO, other.get("spend"));
        assertEquals(BigDecimal.ZERO, other.get("amount"));
        assertEquals(BigDecimal.ZERO, other.get("instantDiscount"));
        assertEquals(BigDecimal.ZERO, other.get("convenienceFee"));
        assertEquals(BigDecimal.ZERO, other.get("txnCount"));
        // per-line measures still present on the second line
        assertEquals(new BigDecimal("40"), other.get("earned"));
        assertEquals(new BigDecimal("10.00"), other.get("valueInr"));
    }

    @Test
    void ineligiblePrimaryLineKeepsAmountButZeroesSpendDiscountFeeAndCount() {
        Account card = account("Card", null);
        RewardLineResponse l = line(UUID.randomUUID(), null, null, "0", "RUPEES", RewardLineReason.TRANSFER_OR_PAYMENT, null);
        stub(card, Map.of(), List.of(reportLine(l, true, false, "0", null, null, List.of())));

        Map<String, Object> row = datasource.rows().get(0);
        assertEquals(new BigDecimal("10000.00"), row.get("amount"));   // amount rides on primary regardless of eligibility
        assertEquals(BigDecimal.ZERO, row.get("spend"));
        assertEquals(BigDecimal.ZERO, row.get("instantDiscount"));
        assertEquals(BigDecimal.ZERO, row.get("convenienceFee"));
        assertEquals(BigDecimal.ZERO, row.get("txnCount"));
    }

    @Test
    void eligiblePrimaryWithNullDiscountAndFeeUsesZero() {
        Account card = account("Card", null);
        RewardLineResponse l = line(UUID.randomUUID(), UUID.randomUUID(), "R", "10.00", "RUPEES", RewardLineReason.MATCHED, null);
        stub(card, Map.of(), List.of(reportLine(l, true, true, "9000", null, null, List.of())));

        Map<String, Object> row = datasource.rows().get(0);
        assertEquals(BigDecimal.ZERO, row.get("instantDiscount"));
        assertEquals(BigDecimal.ZERO, row.get("convenienceFee"));
        assertEquals(new BigDecimal("10.00"), row.get("netValueInr"));
    }

    @Test
    void netValueAddsDiscountAndSubtractsFeeOnlyOnPrimaryEligibleLine() {
        Account card = account("Card", null);
        UUID txn = UUID.randomUUID();
        RewardLineResponse first = line(txn, UUID.randomUUID(), "A", "100.00", "RUPEES", RewardLineReason.MATCHED, null);
        RewardLineResponse second = line(txn, UUID.randomUUID(), "B", "20.00", "RUPEES", RewardLineReason.MATCHED, null);
        stub(card, Map.of(), List.of(
                reportLine(first, true, true, "9000", "30.00", "12.50", List.of()),
                reportLine(second, false, true, "9000", "30.00", "12.50", List.of())));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(new BigDecimal("117.50"), rows.get(0).get("netValueInr"));  // 100 + 30 - 12.50
        assertEquals(new BigDecimal("20.00"), rows.get(1).get("netValueInr"));   // no double count
    }

    @Test
    void ruleLabelComesFromSupportAndFallsBackToRuleNameThenNone() {
        Account card = account("Card", null);
        UUID labelled = UUID.randomUUID(), unlabelled = UUID.randomUUID(), noName = UUID.randomUUID();
        UUID t = UUID.randomUUID();
        stub(card, Map.of(labelled, "Base · Card"), List.of(
                reportLine(line(t, labelled, "Base", "1", "RUPEES", RewardLineReason.MATCHED, null), true, true, "1", null, null, List.of()),
                reportLine(line(t, unlabelled, "Raw name", "1", "RUPEES", RewardLineReason.MATCHED, null), false, true, "1", null, null, List.of()),
                reportLine(line(t, noName, null, "1", "RUPEES", RewardLineReason.MATCHED, null), false, true, "1", null, null, List.of())));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals("Base · Card", rows.get(0).get("rule"));
        assertEquals("Raw name", rows.get(1).get("rule"));
        assertEquals("(none)", rows.get(2).get("rule"));
    }

    @Test
    void cardholderFallsBackToUnattributed() {
        Account card = account("Card", null);
        stub(card, Map.of(), List.of(
                reportLine(line(UUID.randomUUID(), null, null, "0", "RUPEES", RewardLineReason.NO_RULE, "Wife ••5678"), true, true, "1", null, null, List.of()),
                reportLine(line(UUID.randomUUID(), null, null, "0", "RUPEES", RewardLineReason.NO_RULE, null), true, true, "1", null, null, List.of())));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals("Wife ••5678", rows.get(0).get("cardholder"));
        assertEquals("Unattributed", rows.get(1).get("cardholder"));
    }

    @Test
    void categoryIsAListAndCycleAndRewardYearAreFormattedPeriods() {
        Account card = account("Card", null);
        stub(card, Map.of(), List.of(
                reportLine(line(UUID.randomUUID(), null, null, "0", "RUPEES", RewardLineReason.NO_RULE, null), true, true, "1", null, null,
                        List.of("Dining", "Travel"))));

        Map<String, Object> row = datasource.rows().get(0);
        assertEquals(List.of("Dining", "Travel"), row.get("category"));
        assertEquals("2025-01-01 → 2025-01-31", row.get("cycle"));
        assertEquals("2024-06-01 → 2025-05-31", row.get("rewardYear"));
    }

    @Test
    void reportLinesReturningNullSkipsTheCard() {
        Account card = account("Card", null);
        stub(card, Map.of(), null);
        assertTrue(datasource.rows().isEmpty());
    }

    // ---------- DateHint narrowing ----------

    @Test
    void effectiveDateHintNarrowsEvaluationRange() {
        Account card = account("Card", null);
        stub(card, Map.of(), List.of());
        datasource.rows(new DateHint("effectiveDate", LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28)));
        verify(rewardCalculationService).reportLines(card.getId(), LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28));
    }

    @Test
    void hintWiderThanBoundsIsClampedToBounds() {
        Account card = account("Card", null);
        stub(card, Map.of(), List.of());
        datasource.rows(new DateHint("effectiveDate", LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1)));
        verify(rewardCalculationService).reportLines(card.getId(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31));
    }

    @Test
    void hintOnOtherFieldIsIgnored() {
        Account card = account("Card", null);
        stub(card, Map.of(), List.of());
        datasource.rows(new DateHint("transactionDate", LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28)));
        verify(rewardCalculationService).reportLines(card.getId(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31));
    }

    @Test
    void nullHintUsesFullBounds() {
        Account card = account("Card", null);
        stub(card, Map.of(), List.of());
        datasource.rows(null);
        verify(rewardCalculationService).reportLines(card.getId(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31));
    }

    @Test
    void emptyIntersectionSkipsTheCardWithoutEvaluating() {
        Account card = account("Card", null);
        stub(card, Map.of(), List.of());
        List<Map<String, Object>> rows = datasource.rows(
                new DateHint("effectiveDate", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        assertTrue(rows.isEmpty());
        verify(rewardCalculationService, never()).reportLines(any(), any(), any());

        List<Map<String, Object>> before = datasource.rows(
                new DateHint("effectiveDate", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)));
        assertTrue(before.isEmpty());
        verify(rewardCalculationService, never()).reportLines(any(), any(), any());
    }
}
