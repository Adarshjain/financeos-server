package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.reward.dto.RewardReportResponse.MilestoneStatus;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.reward.MilestoneBasis;
import com.financeos.domain.reward.MilestonePayoutType;
import com.financeos.domain.reward.MilestoneWindow;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardType;
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

class RewardMilestonesDatasourceTest {

    private static final LocalDate WS = LocalDate.of(2026, 3, 1);
    private static final LocalDate WE = LocalDate.of(2026, 3, 31);

    private RewardCalculationService service;
    private RewardReportSupport support;
    private RewardMilestonesDatasource datasource;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        service = mock(RewardCalculationService.class);
        support = mock(RewardReportSupport.class);
        datasource = new RewardMilestonesDatasource(service, support);
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

    private static MilestoneStatus status(UUID id, String name, String threshold, String progress, boolean achieved,
                                          MilestonePayoutType payoutType, RewardType rewardType, String payout) {
        return new MilestoneStatus(id, name, MilestoneWindow.CALENDAR_MONTH, WS, WE, MilestoneBasis.SPEND,
                threshold == null ? null : new BigDecimal(threshold), null, new BigDecimal(progress), achieved,
                payoutType, rewardType, payout == null ? null : new BigDecimal(payout),
                achieved ? WE : null, achieved && payoutType == MilestonePayoutType.CASH_VALUE);
    }

    private void stub(Account account, Map<UUID, String> labels, List<MilestoneStatus> statuses) {
        when(support.milestoneAccounts(userId)).thenReturn(List.of(account));
        when(support.milestoneLabels(List.of(account))).thenReturn(labels);
        when(support.bounds(account.getId())).thenReturn(
                new RewardReportSupport.DateBounds(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));
        when(service.milestoneStatuses(account.getId(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)))
                .thenReturn(statuses);
    }

    @Test
    void catalogShape() {
        assertEquals("reward_milestones", datasource.name());
        assertEquals("Reward Milestones", datasource.label());
        List<FieldDef> fields = datasource.fields();
        List<String> names = fields.stream().map(FieldDef::name).toList();
        assertEquals(List.of("windowStart", "windowEnd", "payoutDate", "card", "milestone", "windowType", "basis",
                "payoutType", "rewardType", "achieved", "threshold", "progress", "progressPct", "payoutValue",
                "payoutValueInr"), names);
        assertEquals(names.size(), new HashSet<>(names).size());

        FieldDef milestone = fields.stream().filter(f -> f.name().equals("milestone")).findFirst().orElseThrow();
        assertEquals(FieldType.ENUM, milestone.type());
        assertTrue(milestone.dynamic());
        FieldDef card = fields.stream().filter(f -> f.name().equals("card")).findFirst().orElseThrow();
        assertTrue(card.dynamic());
        FieldDef achieved = fields.stream().filter(f -> f.name().equals("achieved")).findFirst().orElseThrow();
        assertEquals(FieldType.ENUM, achieved.type());
        assertEquals(FieldRole.DIMENSION, achieved.role());
        assertEquals(List.of("Yes", "No"), achieved.values());
        assertEquals(List.of(ReportType.CHART, ReportType.TABLE), achieved.allowedInReports());
        FieldDef inr = fields.stream().filter(f -> f.name().equals("payoutValueInr")).findFirst().orElseThrow();
        assertEquals("currency", inr.format());
        FieldDef pct = fields.stream().filter(f -> f.name().equals("progressPct")).findFirst().orElseThrow();
        assertEquals("percent", pct.format());
        FieldDef windowType = fields.stream().filter(f -> f.name().equals("windowType")).findFirst().orElseThrow();
        assertEquals(List.of("CALENDAR_MONTH", "STATEMENT_CYCLE", "QUARTER", "CALENDAR_YEAR", "ANNIVERSARY_YEAR", "ONE_TIME"),
                windowType.values());
    }

    @Test
    void noUserGivesNoRows() {
        UserContext.clear();
        assertTrue(datasource.rows().isEmpty());
        verifyNoInteractions(support, service);
    }

    @Test
    void noMilestoneCardsGivesNoRows() {
        when(support.milestoneAccounts(userId)).thenReturn(List.of());
        assertTrue(datasource.rows().isEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void achievedCashValueRupeesMilestoneRowMapsEveryField() {
        Account card = account("Infinia", "0.25");
        UUID id = UUID.randomUUID();
        stub(card, Map.of(id, "Spend 1L · Infinia"),
                List.of(status(id, "Spend 1L", "100000", "125000", true, MilestonePayoutType.CASH_VALUE, RewardType.CASH, "1500")));

        Map<String, Object> row = datasource.rows().get(0);
        assertEquals(id + "_" + WS, row.get("id"));
        assertEquals(WS, row.get("windowStart"));
        assertEquals(WE, row.get("windowEnd"));
        assertEquals(WE, row.get("payoutDate"));
        assertEquals("Infinia", row.get("card"));
        assertEquals("Spend 1L · Infinia", row.get("milestone"));
        assertEquals("CALENDAR_MONTH", row.get("windowType"));
        assertEquals("SPEND", row.get("basis"));
        assertEquals("CASH_VALUE", row.get("payoutType"));
        assertEquals("CASH", row.get("rewardType"));
        assertEquals("Yes", row.get("achieved"));
        assertEquals(new BigDecimal("100000"), row.get("threshold"));
        assertEquals(new BigDecimal("125000"), row.get("progress"));
        assertEquals(new BigDecimal("125.00"), row.get("progressPct"));
        assertEquals(new BigDecimal("1500"), row.get("payoutValue"));
        assertEquals(new BigDecimal("1500.00"), row.get("payoutValueInr"));
    }

    @Test
    void achievedPointsMilestoneIsValuedAtPointValue() {
        Account card = account("Infinia", "0.25");
        UUID id = UUID.randomUUID();
        stub(card, Map.of(), List.of(
                status(id, "Bonus pts", "50000", "60000", true, MilestonePayoutType.CASH_VALUE, RewardType.POINTS, "2000")));

        Map<String, Object> row = datasource.rows().get(0);
        assertEquals("POINTS", row.get("rewardType"));
        assertEquals(new BigDecimal("500.00"), row.get("payoutValueInr"));
        assertEquals("Bonus pts", row.get("milestone"));  // label falls back to the milestone's own name
    }

    @Test
    void achievedPointsMilestoneWithoutPointValueIsWorthZero() {
        Account card = account("Generic", null);
        UUID id = UUID.randomUUID();
        stub(card, Map.of(), List.of(
                status(id, "Bonus pts", "50000", "60000", true, MilestonePayoutType.CASH_VALUE, RewardType.POINTS, "2000")));
        assertEquals(new BigDecimal("0.00"), datasource.rows().get(0).get("payoutValueInr"));
        assertEquals(new BigDecimal("2000"), datasource.rows().get(0).get("payoutValue"));
    }

    @Test
    void infoTrackerAchievedPaysNothing() {
        Account card = account("Infinia", "0.25");
        UUID id = UUID.randomUUID();
        stub(card, Map.of(), List.of(
                status(id, "Fee waiver", "300000", "310000", true, MilestonePayoutType.INFO_TRACKER, RewardType.CASH, "999")));
        assertEquals(new BigDecimal("0.00"), datasource.rows().get(0).get("payoutValueInr"));
        assertEquals("INFO_TRACKER", datasource.rows().get(0).get("payoutType"));
    }

    @Test
    void unachievedCashValueMilestonePaysNothingAndHasNoPayoutDate() {
        Account card = account("Infinia", null);
        UUID id = UUID.randomUUID();
        stub(card, Map.of(), List.of(
                status(id, "Spend 1L", "100000", "25000", false, MilestonePayoutType.CASH_VALUE, RewardType.CASH, "1500")));
        Map<String, Object> row = datasource.rows().get(0);
        assertEquals(new BigDecimal("0.00"), row.get("payoutValueInr"));
        assertEquals("No", row.get("achieved"));
        assertNull(row.get("payoutDate"));
        assertEquals(new BigDecimal("25.00"), row.get("progressPct"));
    }

    @Test
    void progressPctIsNullForZeroOrMissingThreshold() {
        Account card = account("Infinia", null);
        UUID zero = UUID.randomUUID(), missing = UUID.randomUUID();
        stub(card, Map.of(), List.of(
                status(zero, "Zero", "0", "5", false, MilestonePayoutType.INFO_TRACKER, RewardType.CASH, null),
                status(missing, "Missing", null, "5", false, MilestonePayoutType.INFO_TRACKER, RewardType.CASH, null)));
        List<Map<String, Object>> rows = datasource.rows();
        assertNull(rows.get(0).get("progressPct"));
        assertNull(rows.get(1).get("progressPct"));
    }

    @Test
    void progressPctRoundsHalfUpToTwoDecimals() {
        Account card = account("Infinia", null);
        stub(card, Map.of(), List.of(
                status(UUID.randomUUID(), "M", "3", "1", false, MilestonePayoutType.INFO_TRACKER, RewardType.CASH, null)));
        assertEquals(new BigDecimal("33.33"), datasource.rows().get(0).get("progressPct"));
    }

    @Test
    void cardWithNoTransactionsIsStillEvaluatedAtToday() {
        Account card = account("Fresh", null);
        LocalDate today = LocalDate.now();
        when(support.milestoneAccounts(userId)).thenReturn(List.of(card));
        when(support.milestoneLabels(List.of(card))).thenReturn(Map.of());
        when(support.bounds(card.getId())).thenReturn(null);
        UUID id = UUID.randomUUID();
        when(service.milestoneStatuses(card.getId(), today, today)).thenReturn(List.of(
                status(id, "Spend", "100", "0", false, MilestonePayoutType.INFO_TRACKER, RewardType.CASH, null)));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(1, rows.size());
        assertEquals(new BigDecimal("0.00"), rows.get(0).get("progressPct"));
        verify(service).milestoneStatuses(card.getId(), today, today);
    }

    @Test
    void nullStatusListSkipsTheCard() {
        Account card = account("Infinia", null);
        stub(card, Map.of(), null);
        assertTrue(datasource.rows().isEmpty());
    }

    @Test
    void everyCatalogFieldPresentOnEveryRowAndIdsUnique() {
        Account card = account("Infinia", "0.25");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        stub(card, Map.of(), List.of(
                status(a, "A", "10", "10", true, MilestonePayoutType.CASH_VALUE, RewardType.CASH, "5"),
                status(b, "B", "10", "1", false, MilestonePayoutType.INFO_TRACKER, RewardType.POINTS, null)));
        List<Map<String, Object>> rows = datasource.rows();
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> row : rows) {
            assertTrue(ids.add(row.get("id")));
            for (FieldDef f : datasource.fields()) {
                assertTrue(row.containsKey(f.name()), f.name());
            }
        }
    }

    @Test
    void rowsCarryStableCardAndMilestoneIds() {
        Account card = account("Infinia", null);
        UUID id = UUID.randomUUID();
        stub(card, Map.of(id, "Spend 1L · Infinia"),
                List.of(status(id, "Spend 1L", "100", "1", false, MilestonePayoutType.INFO_TRACKER, RewardType.CASH, null)));
        Map<String, Object> row = datasource.rows().get(0);
        assertEquals(card.getId().toString(), row.get("cardId"));
        assertEquals(id.toString(), row.get("milestoneId"));
    }

    @Test
    void milestoneIdIsNullWhenStatusHasNoId() {
        Account card = account("Infinia", null);
        stub(card, new java.util.HashMap<>(), List.of(
                status(null, "Anon", "100", "1", false, MilestonePayoutType.INFO_TRACKER, RewardType.CASH, null)));
        assertNull(datasource.rows().get(0).get("milestoneId"));
    }

    @Test
    void catalogDeclaresIdFieldsForCardAndMilestoneOnly() {
        Map<String, String> ids = new java.util.HashMap<>();
        for (FieldDef f : datasource.fields()) {
            if (f.idField() != null) ids.put(f.name(), f.idField());
        }
        assertEquals(Map.of("card", "cardId", "milestone", "milestoneId"), ids);
    }
}
