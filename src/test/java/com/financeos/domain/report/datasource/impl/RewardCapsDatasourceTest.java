package com.financeos.domain.report.datasource.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import com.financeos.domain.report.datasource.FieldRole;
import com.financeos.domain.report.datasource.FieldType;
import com.financeos.domain.reward.CapWindow;
import com.financeos.domain.reward.RewardCalculationService;
import com.financeos.domain.reward.RewardCalculationService.CapUsage;
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

class RewardCapsDatasourceTest {

    private static final LocalDate WS = LocalDate.of(2026, 3, 1);
    private static final LocalDate WE = LocalDate.of(2026, 3, 31);

    private RewardCalculationService service;
    private RewardReportSupport support;
    private RewardCapsDatasource datasource;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        service = mock(RewardCalculationService.class);
        support = mock(RewardReportSupport.class);
        datasource = new RewardCapsDatasource(service, support);
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

    private static CapUsage ruleCap(UUID ruleId, String ruleName, String unit, String cap, String used) {
        return new CapUsage(ruleId, null, ruleName, CapWindow.CALENDAR_MONTH, WS, WE, false,
                null, null, unit, new BigDecimal(cap), new BigDecimal(used));
    }

    private void stub(Account account, Map<UUID, String> labels, List<CapUsage> usages) {
        when(support.ruleAccounts(userId)).thenReturn(List.of(account));
        when(support.ruleLabels(List.of(account))).thenReturn(labels);
        var bounds = new RewardReportSupport.DateBounds(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        when(support.bounds(account.getId())).thenReturn(bounds);
        when(service.capUsage(account.getId(), bounds.from(), bounds.to())).thenReturn(usages);
    }

    @Test
    void catalogShape() {
        assertEquals("reward_caps", datasource.name());
        assertEquals("Reward Caps", datasource.label());
        List<FieldDef> fields = datasource.fields();
        List<String> names = fields.stream().map(FieldDef::name).toList();
        assertEquals(List.of("windowStart", "windowEnd", "card", "cap", "capType", "window", "cardholder", "unit",
                "capHit", "cycleFallback", "capLimit", "used", "remaining", "utilizationPct", "usedValueInr"), names);
        assertEquals(names.size(), new HashSet<>(names).size());

        FieldDef cap = fields.stream().filter(f -> f.name().equals("cap")).findFirst().orElseThrow();
        assertTrue(cap.dynamic());
        FieldDef cardholder = fields.stream().filter(f -> f.name().equals("cardholder")).findFirst().orElseThrow();
        assertTrue(cardholder.dynamic());
        FieldDef capType = fields.stream().filter(f -> f.name().equals("capType")).findFirst().orElseThrow();
        assertEquals(List.of("RULE", "SHARED_BUCKET"), capType.values());
        FieldDef window = fields.stream().filter(f -> f.name().equals("window")).findFirst().orElseThrow();
        assertEquals(List.of("DAY", "CALENDAR_MONTH", "STATEMENT_CYCLE", "QUARTER", "CALENDAR_YEAR", "ANNIVERSARY_YEAR"),
                window.values());
        FieldDef unit = fields.stream().filter(f -> f.name().equals("unit")).findFirst().orElseThrow();
        assertEquals(List.of("RUPEES", "POINTS"), unit.values());
        for (String flag : List.of("capHit", "cycleFallback")) {
            FieldDef f = fields.stream().filter(x -> x.name().equals(flag)).findFirst().orElseThrow();
            assertEquals(FieldType.BOOLEAN, f.type());
            assertEquals(FieldRole.FILTER, f.role());
        }
        FieldDef pct = fields.stream().filter(f -> f.name().equals("utilizationPct")).findFirst().orElseThrow();
        assertEquals("percent", pct.format());
        FieldDef inr = fields.stream().filter(f -> f.name().equals("usedValueInr")).findFirst().orElseThrow();
        assertEquals("currency", inr.format());
    }

    @Test
    void noUserGivesNoRows() {
        UserContext.clear();
        assertTrue(datasource.rows().isEmpty());
        verifyNoInteractions(support, service);
    }

    @Test
    void noRuleCardsGivesNoRows() {
        when(support.ruleAccounts(userId)).thenReturn(List.of());
        assertTrue(datasource.rows().isEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void ruleCapRowMapsEveryField() {
        Account card = account("Infinia", "0.25");
        UUID ruleId = UUID.randomUUID();
        stub(card, Map.of(ruleId, "Dining 5% · Infinia"),
                List.of(ruleCap(ruleId, "Dining 5%", "RUPEES", "500", "125")));

        Map<String, Object> row = datasource.rows().get(0);
        assertEquals(card.getId() + "_" + ruleId + "_" + WS + "_null", row.get("id"));
        assertEquals(WS, row.get("windowStart"));
        assertEquals(WE, row.get("windowEnd"));
        assertEquals("Infinia", row.get("card"));
        assertEquals("Dining 5% · Infinia", row.get("cap"));   // rule label from support
        assertEquals("RULE", row.get("capType"));
        assertEquals("CALENDAR_MONTH", row.get("window"));
        assertEquals("All cardholders", row.get("cardholder"));
        assertEquals("RUPEES", row.get("unit"));
        assertEquals(false, row.get("capHit"));
        assertEquals(false, row.get("cycleFallback"));
        assertEquals(new BigDecimal("500"), row.get("capLimit"));
        assertEquals(new BigDecimal("125"), row.get("used"));
        assertEquals(new BigDecimal("375"), row.get("remaining"));
        assertEquals(new BigDecimal("25.00"), row.get("utilizationPct"));
        assertEquals(new BigDecimal("125.00"), row.get("usedValueInr"));
    }

    @Test
    void ruleCapLabelFallsBackToRuleNameWhenUnlabelled() {
        Account card = account("Infinia", null);
        UUID ruleId = UUID.randomUUID();
        stub(card, Map.of(), List.of(ruleCap(ruleId, "Dining 5%", "RUPEES", "500", "1")));
        assertEquals("Dining 5%", datasource.rows().get(0).get("cap"));
    }

    @Test
    void sharedBucketUsesBucketNameAndBucketIdKey() {
        Account card = account("Infinia", null);
        CapUsage bucket = new CapUsage(null, "Shared 1000", "Dining 5%", CapWindow.STATEMENT_CYCLE, WS, WE, true,
                null, null, "RUPEES", new BigDecimal("1000"), new BigDecimal("400"));
        stub(card, Map.of(), List.of(bucket));

        Map<String, Object> row = datasource.rows().get(0);
        assertEquals("Shared 1000", row.get("cap"));
        assertEquals("SHARED_BUCKET", row.get("capType"));
        assertEquals("STATEMENT_CYCLE", row.get("window"));
        assertEquals(true, row.get("cycleFallback"));
        assertEquals(card.getId() + "_b:Shared 1000_" + WS + "_null", row.get("id"));
    }

    @Test
    void perCardholderCapShowsCardholderLabelAndIdKey() {
        Account card = account("Infinia", null);
        UUID ruleId = UUID.randomUUID(), holder = UUID.randomUUID();
        CapUsage usage = new CapUsage(ruleId, null, "Dining", CapWindow.CALENDAR_MONTH, WS, WE, false,
                holder, "Wife", "RUPEES", new BigDecimal("500"), new BigDecimal("100"));
        stub(card, Map.of(), List.of(usage));

        Map<String, Object> row = datasource.rows().get(0);
        assertEquals("Wife", row.get("cardholder"));
        assertTrue(((String) row.get("id")).endsWith("_" + holder));
    }

    @Test
    void capHitWhenUsedReachesOrExceedsCapAndRemainingNeverNegative() {
        Account card = account("Infinia", null);
        UUID exact = UUID.randomUUID(), over = UUID.randomUUID(), under = UUID.randomUUID();
        stub(card, Map.of(), List.of(
                ruleCap(exact, "Exact", "RUPEES", "500", "500"),
                ruleCap(over, "Over", "RUPEES", "500", "650"),
                ruleCap(under, "Under", "RUPEES", "500", "499")));

        List<Map<String, Object>> rows = datasource.rows();
        assertEquals(true, rows.get(0).get("capHit"));
        assertEquals(new BigDecimal("0"), rows.get(0).get("remaining"));
        assertEquals(true, rows.get(1).get("capHit"));
        assertEquals(BigDecimal.ZERO, rows.get(1).get("remaining"));       // never negative
        assertEquals(new BigDecimal("130.00"), rows.get(1).get("utilizationPct"));
        assertEquals(false, rows.get(2).get("capHit"));
        assertEquals(new BigDecimal("1"), rows.get(2).get("remaining"));
    }

    @Test
    void zeroCapHasNullUtilization() {
        Account card = account("Infinia", null);
        stub(card, Map.of(), List.of(ruleCap(UUID.randomUUID(), "Zero", "RUPEES", "0", "0")));
        Map<String, Object> row = datasource.rows().get(0);
        assertNull(row.get("utilizationPct"));
        assertEquals(true, row.get("capHit"));
    }

    @Test
    void pointsCapUsedIsValuedAndUnvaluedIsZero() {
        Account valued = account("Valued", "0.5");
        UUID r1 = UUID.randomUUID();
        stub(valued, Map.of(), List.of(ruleCap(r1, "Pts", "POINTS", "2000", "1000")));
        Map<String, Object> row = datasource.rows().get(0);
        assertEquals("POINTS", row.get("unit"));
        assertEquals(new BigDecimal("500.00"), row.get("usedValueInr"));
        assertEquals(new BigDecimal("50.00"), row.get("utilizationPct"));

        Account unvalued = account("Unvalued", null);
        UUID r2 = UUID.randomUUID();
        stub(unvalued, Map.of(), List.of(ruleCap(r2, "Pts", "POINTS", "2000", "1000")));
        assertEquals(new BigDecimal("0.00"), datasource.rows().get(0).get("usedValueInr"));
    }

    @Test
    void cardWithNoTransactionsIsSkipped() {
        Account card = account("Fresh", null);
        when(support.ruleAccounts(userId)).thenReturn(List.of(card));
        when(support.ruleLabels(List.of(card))).thenReturn(Map.of());
        when(support.bounds(card.getId())).thenReturn(null);
        assertTrue(datasource.rows().isEmpty());
        verifyNoInteractions(service);
    }

    @Test
    void nullUsageListSkipsTheCard() {
        Account card = account("Infinia", null);
        stub(card, Map.of(), null);
        assertTrue(datasource.rows().isEmpty());
    }

    @Test
    void everyCatalogFieldPresentOnEveryRowAndIdsUnique() {
        Account card = account("Infinia", "0.25");
        stub(card, Map.of(), List.of(
                ruleCap(UUID.randomUUID(), "A", "RUPEES", "10", "1"),
                new CapUsage(null, "B", "R", CapWindow.QUARTER, WS, WE, false, UUID.randomUUID(), "X", "POINTS",
                        new BigDecimal("10"), new BigDecimal("2"))));
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> row : datasource.rows()) {
            assertTrue(ids.add(row.get("id")));
            for (FieldDef f : datasource.fields()) {
                assertTrue(row.containsKey(f.name()), f.name());
            }
        }
    }
}
