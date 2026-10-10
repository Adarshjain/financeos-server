package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.Entry;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.ParamSpec;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.PositionsDatasource;
import com.financeos.domain.report.datasource.impl.RewardCapsDatasource;
import com.financeos.domain.report.datasource.impl.RewardEarningsDatasource;
import com.financeos.domain.report.datasource.impl.RewardMilestonesDatasource;
import com.financeos.domain.report.datasource.impl.RewardReportSupport;
import com.financeos.domain.report.datasource.impl.TransactionsDatasource;
import com.financeos.domain.report.definition.ReportDefinitions;
import com.financeos.domain.report.engine.DateRangeResolver;
import com.financeos.domain.report.engine.SqlPredicates;
import com.financeos.domain.reward.RewardCalculationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The fourteen built-ins added for the widgets expansion: catalog fields, params, templates, availability. */
class BuiltinWidgetRegistryRound2Test {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final UUID CARD = UUID.fromString("0b3c7a52-6f43-4d61-9a0f-1c2d3e4f5a6b");

    private final ObjectMapper mapper = new ObjectMapper();
    private final BuiltinWidgetRegistry registry = new BuiltinWidgetRegistry(mapper);

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void fixToday() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant(), ZoneId.of("Asia/Kolkata")));
    }

    private ObjectNode params() {
        return mapper.createObjectNode();
    }

    /** key, kind, minW, category, view, href */
    private void assertShape(String key, String kind, int minW, String category, String view, String href) {
        Entry e = registry.require(key);
        assertEquals(kind, e.kind(), key);
        assertEquals(minW, e.minW(), key);
        assertEquals(category, e.category(), key);
        assertEquals(view, e.view(), key);
        assertEquals(href, e.href(), key);
        assertTrue(e.label() != null && !e.label().isBlank(), key);
        if (e.subtitle() != null) {
            assertTrue(e.subtitle().split(" ").length <= 4, key + " subtitle is short: " + e.subtitle());
        }
    }

    @Test
    void everyNewEntryHasItsKindWidthCategoryViewAndHref() {
        String c = BuiltinWidgetRegistry.KIND_COMPONENT;
        String t = BuiltinWidgetRegistry.KIND_TEMPLATE;
        assertShape("card_utilisation", c, 50, "cards_rewards", null, "/accounts");
        assertShape("milestone_progress", t, 50, "cards_rewards", "progress_list", "/rewards");
        assertShape("cap_headroom", t, 50, "cards_rewards", "cap_list", "/rewards");
        assertShape("rewards_earned", t, 50, "cards_rewards", "rewards_fy", "/rewards");
        assertShape("spend_heatmap", t, 100, "spending", "heatmap", "/transactions");
        assertShape("portfolio_snapshot", c, 50, "investments", null, "/investments");
        assertShape("top_movers", c, 50, "investments", null, "/investments");
        assertShape("allocation", t, 50, "investments", "allocation", "/investments");
        assertShape("tax_harvest", c, 50, "investments", null, "/investments");
        assertShape("loan_payoff", c, 50, "loans_lending", null, "/loans");
        assertShape("lending_balances", c, 50, "loans_lending", null, "/loans/lendings");
        assertShape("account_tile", c, 25, "overview", null, null);
        assertShape("emergency_fund", c, 50, "overview", null, "/accounts");
        assertShape("shortcuts", c, 25, "shortcuts", null, null);
    }

    @Test
    void templatesRunOverTheirDatasourcesAsTheirReportType() {
        assertEquals(Map.of(
                        "milestone_progress", "reward_milestones:TABLE",
                        "cap_headroom", "reward_caps:TABLE",
                        "rewards_earned", "reward_earnings:TABLE",
                        "spend_heatmap", "transactions:CHART",
                        "allocation", "positions:CHART"),
                Map.of(
                        "milestone_progress", ds("milestone_progress"),
                        "cap_headroom", ds("cap_headroom"),
                        "rewards_earned", ds("rewards_earned"),
                        "spend_heatmap", ds("spend_heatmap"),
                        "allocation", ds("allocation")));
        for (String key : List.of("card_utilisation", "portfolio_snapshot", "top_movers", "tax_harvest", "loan_payoff",
                "lending_balances", "account_tile", "emergency_fund", "shortcuts")) {
            Entry e = registry.require(key);
            assertNull(e.templateType(), key);
            assertNull(e.datasource(), key);
            assertNull(e.templateDefinition(), key);
            assertThrows(ValidationException.class, () -> registry.resolveDefinition(e, null), key);
        }
    }

    private String ds(String key) {
        Entry e = registry.require(key);
        return e.datasource() + ":" + e.templateType().name();
    }

    @Test
    void descriptionsAreThePickerTexts() {
        assertEquals("How much of each credit card's limit you're using right now, from live balances. Banks and "
                + "bureaus watch utilisation above 30%; this flags cards over it.", registry.require("card_utilisation").description());
        assertEquals("How close each card is to its next spend milestone, how many days are left, and how much you'd "
                + "need to spend per day to reach it.", registry.require("milestone_progress").description());
        assertEquals("Reward caps that are nearly used up this cycle, so you know when to move a category of spend to "
                + "another card.", registry.require("cap_headroom").description());
        assertEquals("Current value, amount invested, unrealised gain and XIRR across all brokers, plus the change since "
                + "the last evening price update.", registry.require("portfolio_snapshot").description());
        assertEquals("Your holdings that moved most since the previous evening price update. Mutual fund NAVs update a "
                + "day late.", registry.require("top_movers").description());
        assertEquals("How your portfolio splits across equity, debt, hybrid, gold and international, at current market "
                + "value.", registry.require("allocation").description());
        assertEquals("Capital gains booked this financial year, how much of the ₹1.25L LTCG exemption is left, and "
                + "which lots are worth selling — or waiting on — to use it. Not tax advice.",
                registry.require("tax_harvest").description());
        assertEquals("Daily spending as a calendar, darker on heavier days. Tap a day to see its transactions. Transfers "
                + "between your own accounts are left out.", registry.require("spend_heatmap").description());
        assertEquals("For each loan: what's left, how much principal you've repaid, the expected payoff date and the "
                + "interest still to pay.", registry.require("loan_payoff").description());
        assertEquals("Who owes you and whom you owe, biggest first, with Settle up for each person.",
                registry.require("lending_balances").description());
        assertEquals("One account's balance with a 30-day trend. Add one for each account you watch closely.",
                registry.require("account_tile").description());
        assertEquals("How many months your bank and cash balances would cover at your usual monthly outflow (card bills "
                + "and EMIs included). Accounts marked excluded are left out.", registry.require("emergency_fund").description());
        assertEquals("Rewards earned this financial year, in rupees where the card has a point value and in points where "
                + "it doesn't.", registry.require("rewards_earned").description());
        assertEquals("Your own quick links to pages, accounts, reports and actions like Add transaction or Record lending. "
                + "Pick and reorder them yourself.", registry.require("shortcuts").description());
    }

    // ------------------------------------------------------------------ params

    @Test
    void cardWidgetsTakeAnOptionalCreditCard() {
        for (String key : List.of("card_utilisation", "milestone_progress", "cap_headroom")) {
            List<ParamSpec> specs = registry.require(key).params();
            assertEquals(1, specs.size(), key);
            ParamSpec p = specs.get(0);
            assertEquals("accountId", p.name(), key);
            assertEquals(BuiltinWidgetRegistry.PARAM_UUID, p.type(), key);
            assertEquals(BuiltinWidgetRegistry.REF_CREDIT_CARD, p.ref(), key);
            assertFalse(p.required(), key);
        }
        assertTrue(registry.require("rewards_earned").params().isEmpty());
    }

    @Test
    void loanPayoffTakesAnOptionalLoanAndAccountTileARequiredAccount() {
        ParamSpec loan = registry.require("loan_payoff").params().get(0);
        assertEquals("loanId", loan.name());
        assertEquals(BuiltinWidgetRegistry.REF_LOAN, loan.ref());
        assertFalse(loan.required());

        Entry tile = registry.require("account_tile");
        ParamSpec account = tile.params().get(0);
        assertEquals("accountId", account.name());
        assertEquals(BuiltinWidgetRegistry.REF_ACCOUNT, account.ref());
        assertTrue(account.required());
        assertThrows(ValidationException.class, () -> registry.validateParams(tile, null));
        assertThrows(ValidationException.class, () -> registry.validateParams(tile, params().put("accountId", "nope")));
        assertDoesNotThrow(() -> registry.validateParams(tile, params().put("accountId", CARD.toString())));
    }

    @Test
    void topMoversNIsThreeToTenDefaultFive() {
        Entry e = registry.require("top_movers");
        ParamSpec n = e.params().get(0);
        assertEquals("n", n.name());
        assertEquals(BuiltinWidgetRegistry.PARAM_INT, n.type());
        assertEquals(5, n.defaultValue().asInt());
        assertEquals(3, n.min());
        assertEquals(10, n.max());
        assertThrows(ValidationException.class, () -> registry.validateParams(e, params().put("n", 2)));
        assertThrows(ValidationException.class, () -> registry.validateParams(e, params().put("n", 11)));
        assertDoesNotThrow(() -> registry.validateParams(e, params().put("n", 3)));
        assertDoesNotThrow(() -> registry.validateParams(e, params().put("n", 10)));
    }

    @Test
    void heatmapMonthsIsOneToTwelveDefaultSix() {
        Entry e = registry.require("spend_heatmap");
        ParamSpec months = e.params().get(0);
        assertEquals("months", months.name());
        assertEquals(6, months.defaultValue().asInt());
        assertEquals(1, months.min());
        assertEquals(12, months.max());
        assertThrows(ValidationException.class, () -> registry.validateParams(e, params().put("months", 0)));
        assertThrows(ValidationException.class, () -> registry.validateParams(e, params().put("months", 13)));
    }

    @Test
    void shortcutsItemsAreAnOrderedListOfAtMostTwelveReferences() {
        Entry e = registry.require("shortcuts");
        ParamSpec items = e.params().get(0);
        assertEquals("items", items.name());
        assertEquals(BuiltinWidgetRegistry.PARAM_STRING_LIST, items.type());
        assertEquals(12, items.maxItems());
        assertEquals(BuiltinWidgetRegistry.SHORTCUT_ITEM_PATTERN, items.itemPattern());
        List<String> defaults = new ArrayList<>();
        items.defaultValue().forEach(v -> defaults.add(v.asText()));
        assertEquals(List.of("action:add-transaction", "page:/transactions/review", "page:/transactions/import",
                "page:/upcoming"), defaults);

        ObjectNode ok = params();
        ok.set("items", items.defaultValue());
        assertDoesNotThrow(() -> registry.validateParams(e, ok));
        ObjectNode refs = params();
        refs.putArray("items").add("account:" + CARD).add("report:" + UUID.randomUUID()).add("dashboard:" + UUID.randomUUID())
                .add("page:/rewards?account=" + CARD);
        assertDoesNotThrow(() -> registry.validateParams(e, refs));

        ObjectNode tooMany = params();
        ArrayNode many = tooMany.putArray("items");
        for (int i = 0; i < 13; i++) {
            many.add("page:/p" + i);
        }
        assertThrows(ValidationException.class, () -> registry.validateParams(e, tooMany));
        for (String bad : List.of("link:/x", "page:", "page:/has space", "action:<script>")) {
            ObjectNode p = params();
            p.putArray("items").add(bad);
            assertThrows(ValidationException.class, () -> registry.validateParams(e, p), bad);
        }
    }

    @Test
    void entriesWithoutParamsRejectAnyParam() {
        for (String key : List.of("portfolio_snapshot", "allocation", "tax_harvest", "lending_balances",
                "emergency_fund", "rewards_earned")) {
            Entry e = registry.require(key);
            assertTrue(e.params().isEmpty(), key);
            assertThrows(ValidationException.class, () -> registry.validateParams(e, params().put("x", 1)), key);
        }
    }

    // ------------------------------------------------------------------ availability

    private record Facts(Set<AccountType> types, boolean loan, boolean holdings, boolean lending)
            implements BuiltinAvailability.Facts {
        @Override public boolean hasAccountOfType(AccountType type) { return types.contains(type); }
        @Override public boolean hasAnyAccount() { return !types.isEmpty(); }
        @Override public boolean hasLoan() { return loan; }
        @Override public boolean hasHoldings() { return holdings; }
        @Override public boolean hasLending() { return lending; }
    }

    private String reason(String key, Facts facts) {
        return registry.require(key).availability().unavailableReason(facts);
    }

    @Test
    void eachEntryNeedsWhatItShowsAndSaysWhatToAdd() {
        Facts nothing = new Facts(Set.of(), false, false, false);
        Facts bankOnly = new Facts(EnumSet.of(AccountType.bank_account), false, false, false);
        Facts everything = new Facts(EnumSet.allOf(AccountType.class), true, true, true);

        for (String key : List.of("card_utilisation", "milestone_progress", "cap_headroom", "rewards_earned")) {
            assertEquals("Add a credit card first", reason(key, nothing), key);
            assertEquals("Add a credit card first", reason(key, bankOnly), key);
            assertNull(reason(key, everything), key);
            assertEquals("Needs a credit card", registry.require(key).requires(), key);
        }
        for (String key : List.of("portfolio_snapshot", "top_movers", "allocation", "tax_harvest")) {
            assertEquals("Add an investment first", reason(key, bankOnly), key);
            assertNull(reason(key, everything), key);
            assertEquals("Needs investments", registry.require(key).requires(), key);
        }
        for (String key : List.of("account_tile", "spend_heatmap", "emergency_fund")) {
            assertEquals("Add an account first", reason(key, nothing), key);
            assertNull(reason(key, bankOnly), key);
            assertEquals("Needs an account", registry.require(key).requires(), key);
        }
        assertEquals("Add a loan first", reason("loan_payoff", bankOnly));
        assertNull(reason("loan_payoff", everything));
        assertEquals("Needs a loan", registry.require("loan_payoff").requires());
        assertEquals("Record a lending first", reason("lending_balances", bankOnly));
        assertNull(reason("lending_balances", everything));
        assertEquals("Needs a lending entry", registry.require("lending_balances").requires());
        assertSame(BuiltinAvailability.ALWAYS, registry.require("shortcuts").availability());
        assertNull(registry.require("shortcuts").requires());
    }

    // ------------------------------------------------------------------ resolved templates

    private static JsonNode filter(JsonNode definition, String field, String operator) {
        for (JsonNode f : definition.get("filters")) {
            if (field.equals(f.path("field").asText()) && operator.equals(f.path("operator").asText())) {
                return f;
            }
        }
        return null;
    }

    @Test
    void milestoneAndCapTemplatesKeepTodaysWindowsAndAnOptionalCard() {
        fixToday();
        for (String key : List.of("milestone_progress", "cap_headroom")) {
            JsonNode all = registry.resolveDefinition(registry.require(key), null);
            assertEquals("2026-10-10", filter(all, "windowStart", "before").get("value").asText(), key);
            assertEquals("2026-10-08", filter(all, "windowEnd", "after").get("value").asText(), key);
            assertNull(filter(all, "card", "is"), key);

            JsonNode one = registry.resolveDefinition(registry.require(key), params().put("accountId", CARD.toString()));
            assertEquals(CARD.toString(), filter(one, "card", "is").get("value").asText(), key);
            assertNull(filter(registry.require(key).templateDefinition(), "card", "is"), "the template itself is untouched");
        }
        JsonNode milestones = registry.resolveDefinition(registry.require("milestone_progress"), null);
        assertEquals("No", filter(milestones, "achieved", "is").get("value").asText());
        assertEquals("progressPct", milestones.get("sort").get(0).get("key").asText());
        List<String> milestoneColumns = new java.util.ArrayList<>();
        milestones.get("columns").forEach(c -> milestoneColumns.add(c.asText()));
        assertTrue(milestoneColumns.containsAll(List.of("basis", "payoutValue")),
                "the progress view needs the basis (₹ vs count) and the payout value");
        JsonNode caps = registry.resolveDefinition(registry.require("cap_headroom"), null);
        assertEquals("utilizationPct", caps.get("sort").get(0).get("key").asText());
        assertEquals("desc", caps.get("sort").get(0).get("direction").asText());
    }

    @Test
    void heatmapCountsDebitsNotExcludedNotTransfersOverTheLastMonths() {
        Entry e = registry.require("spend_heatmap");
        JsonNode def = registry.resolveDefinition(e, null);
        assertEquals("date", def.get("dimension").get("field").asText());
        assertEquals("day", def.get("dimension").get("granularity").asText());
        assertEquals("spend", def.get("measure").get("field").asText());
        assertEquals("sum", def.get("measure").get("aggregation").asText());
        assertEquals("DEBIT", filter(def, "type", "is").get("value").asText());
        assertFalse(filter(def, "isExcluded", "is").get("value").asBoolean());
        assertFalse(filter(def, "isTransferLeg", "is").get("value").asBoolean());
        assertEquals(6, filter(def, "date", "last_x_months").get("value").get("amount").asInt());
        assertEquals(12, filter(registry.resolveDefinition(e, params().put("months", 12)), "date", "last_x_months")
                .get("value").get("amount").asInt());
    }

    @Test
    void allocationSumsOpenPositionsByAssetClass() {
        JsonNode def = registry.resolveDefinition(registry.require("allocation"), null);
        assertEquals("assetClass", def.get("dimension").get("field").asText());
        assertEquals("currentValue", def.get("measure").get("field").asText());
        assertTrue(filter(def, "isOpen", "is").get("value").asBoolean());
    }

    @Test
    void rewardsEarnedGroupsThisFinancialYearByCard() {
        JsonNode def = registry.resolveDefinition(registry.require("rewards_earned"), null);
        assertEquals("aggregated", def.get("mode").asText());
        assertEquals("card", def.get("rows").get(0).get("field").asText());
        List<String> measures = new ArrayList<>();
        def.get("measures").forEach(m -> measures.add(m.get("field").asText() + ":" + m.get("aggregation").asText()));
        assertEquals(List.of("cashInr:sum", "points:sum", "pointsValueInr:sum", "valueInr:sum"), measures);
        assertTrue(filter(def, "effectiveDate", "current_fy") != null);
    }

    @Test
    void everyTemplateIsAValidDefinitionWithAndWithoutParams() {
        DateRangeResolver dates = new DateRangeResolver(4);
        DatasourceRegistry datasources = new DatasourceRegistry(List.of(
                new RewardMilestonesDatasource(mock(RewardCalculationService.class), mock(RewardReportSupport.class)),
                new RewardCapsDatasource(mock(RewardCalculationService.class), mock(RewardReportSupport.class)),
                new RewardEarningsDatasource(mock(RewardCalculationService.class), mock(RewardReportSupport.class)),
                new PositionsDatasource(mock(InvestmentService.class)),
                new TransactionsDatasource(new SqlPredicates(dates), dates)), new DatasourceCatalog());
        ReportDefinitionValidator validator = new ReportDefinitionValidator(datasources);
        Map<String, ObjectNode> withParams = Map.of(
                "milestone_progress", params().put("accountId", CARD.toString()),
                "cap_headroom", params().put("accountId", CARD.toString()),
                "spend_heatmap", params().put("months", 1));
        for (String key : List.of("milestone_progress", "cap_headroom", "rewards_earned", "spend_heatmap", "allocation")) {
            Entry e = registry.require(key);
            for (JsonNode p : java.util.Arrays.asList(null, withParams.get(key))) {
                JsonNode resolved = registry.resolveDefinition(e, p);
                assertDoesNotThrow(() -> validator.validate(e.datasource(),
                        ReportDefinitions.parse(e.templateType(), resolved, mapper)), key + " " + p);
            }
        }
    }
}
