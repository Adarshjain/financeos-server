package com.financeos.api.widgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Against real persistence (H2): the built-in catalog's availability, every new template built-in's
 * data endpoint, the balance series, the emergency fund (and its drill, which must add up to the same
 * outflow), tax harvesting over real FIFO lots, and the ad-hoc KPI drills the widgets send.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WidgetsServerRound2IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementRepository statementRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private final List<UUID> instrumentIds = new ArrayList<>();
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String email = "widgets-r2-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "widgets-r2-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual != null && actual.isNumber(), "expected " + expected + " but was " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static void assertSame(JsonNode expected, JsonNode actual) {
        assertTrue(expected != null && expected.isNumber(), "expected a number but was " + expected);
        assertDecimal(expected.decimalValue().toPlainString(), actual);
    }

    private UUID bank(ApiTestClient client, String name, int opening, boolean excluded) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name,
                "last4", String.valueOf(1000 + (int) (Math.random() * 8999)), "openingBalance", opening,
                "financialPosition", "asset", "excludeFromNetAsset", excluded));
    }

    private UUID wallet(String name) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "generic", "name", name, "financialPosition", "asset",
                "excludeFromNetAsset", false));
    }

    private UUID card(String name) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", name,
                "last4", String.valueOf(1000 + (int) (Math.random() * 8999)), "creditLimit", 100000,
                "anniversaryDate", today.minusMonths(3).toString(), "financialPosition", "liability",
                "excludeFromNetAsset", false));
    }

    private UUID broker() throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "broker", "name", "R2 Broker", "provider", "Zerodha",
                "clientId", "CL2", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID txn(UUID accountId, LocalDate date, String amount, TransactionType type, boolean excluded) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction(account, date, new BigDecimal(amount), "R2 " + amount, TransactionSource.manual,
                type, false, excluded);
        t.setUser(account.getUser());
        return transactionRepository.save(t).getId();
    }

    private UUID debit(UUID accountId, LocalDate date, String amount) {
        return txn(accountId, date, amount, TransactionType.DEBIT, false);
    }

    /** Puts the transactions in one link of {@code type}, as the link service stores it. */
    private void link(String type, UUID... transactionIds) {
        String linkId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO transaction_links (id, user_id, type, created_by, created_at) VALUES (?, ?, ?, 'USER', CURRENT_TIMESTAMP)",
                linkId, userId.toString(), type);
        boolean anchor = true;
        for (UUID id : transactionIds) {
            jdbc.update("INSERT INTO transaction_link_members (link_id, transaction_id, is_anchor) VALUES (?, ?, ?)",
                    linkId, id.toString(), anchor ? 1 : 0);
            anchor = false;
        }
    }

    private void statement(UUID accountId, LocalDate periodEnd, String closing) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Statement s = new Statement();
        s.setUser(account.getUser());
        s.setAccount(account);
        s.setSource(StatementSource.file_upload);
        s.setStatementType("credit_card");
        s.setPeriodStart(periodEnd.minusMonths(1).plusDays(1));
        s.setPeriodEnd(periodEnd);
        s.setClosingBalance(new BigDecimal(closing));
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(account.getUser());
        d.setTotalAmountDue(new BigDecimal(closing));
        d.setPaymentDueDate(periodEnd.plusDays(18));
        s.setCreditCardDetails(d);
        statementRepository.save(s);
    }

    private JsonNode builtinData(String key, Object params) throws Exception {
        return api.postJson("/api/v1/dashboards/builtins/" + key + "/data",
                params == null ? null : Map.of("params", params), 200);
    }

    private static Map<String, Object> filter(String field, String operator, Object value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", field);
        f.put("operator", operator);
        if (value != null) {
            f.put("value", value);
        }
        return f;
    }

    private JsonNode adHocKpi(String datasource, String measure, List<Map<String, Object>> filters) throws Exception {
        Map<String, Object> definition = Map.of("measure", measure, "aggregation", "sum", "filters", filters);
        return api.postJson("/api/v1/reports/underlying",
                Map.of("type", "KPI", "datasource", datasource, "definition", definition), 200);
    }

    private static Map<String, String> chips(JsonNode underlying) {
        Map<String, String> out = new LinkedHashMap<>();
        underlying.get("filters").forEach(c -> out.put(c.get("field").asText(), c.get("text").asText()));
        return out;
    }

    private UUID instrument(String type, String name) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", type, "name", name));
        if ("stock".equals(type)) {
            body.put("symbol", "R2" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
            body.put("exchange", "NSE");
        }
        UUID id = api.create("/api/v1/instruments", body);
        instrumentIds.add(id);
        return id;
    }

    private void trade(UUID broker, UUID instrument, String type, String qty, String price, LocalDate date) throws Exception {
        api.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", qty, "price", price,
                "tradeDate", date.toString()));
    }

    private void price(UUID instrument, String price) throws Exception {
        api.postJson("/api/v1/instruments/" + instrument + "/price", Map.of("price", new BigDecimal(price),
                "asOf", today.toString()), 200);
    }

    // ------------------------------------------------------------------ catalog

    private Map<String, String> reasons() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode b : api.getJson("/api/v1/dashboards/builtins", 200)) {
            out.put(b.get("key").asText(), b.get("unavailableReason").isNull() ? null : b.get("unavailableReason").asText());
        }
        return out;
    }

    @Test
    void theCatalogSaysWhatEachNewWidgetNeedsUntilTheUserHasIt() throws Exception {
        Map<String, String> before = reasons();
        assertEquals(18, before.size());
        assertEquals("Add a credit card first", before.get("card_utilisation"));
        assertEquals("Add a credit card first", before.get("rewards_earned"));
        assertEquals("Add an investment first", before.get("tax_harvest"));
        assertEquals("Add an account first", before.get("account_tile"));
        assertEquals("Add a loan first", before.get("loan_payoff"));
        assertEquals("Record a lending first", before.get("lending_balances"));
        assertEquals(null, before.get("shortcuts"));
        assertEquals(null, before.get("net_worth"));

        UUID bank = bank(api, "Main", 1000, false);
        card("Card");
        api.create("/api/v1/lendings", Map.of("newCounterpartyName", "Ravi", "direction", "lent", "amount", 500,
                "entryDate", today.toString()));
        UUID stock = instrument("stock", "R2 Catalog Ltd");
        trade(broker(), stock, "buy", "1", "10", today.minusDays(3));

        Map<String, String> after = reasons();
        for (String key : List.of("card_utilisation", "milestone_progress", "cap_headroom", "rewards_earned",
                "portfolio_snapshot", "top_movers", "allocation", "tax_harvest", "account_tile", "spend_heatmap",
                "emergency_fund", "lending_balances")) {
            assertEquals(null, after.get(key), key);
        }
        assertEquals("Add a loan first", after.get("loan_payoff"));
        assertNotNull(bank);

        JsonNode heatmap = null;
        for (JsonNode b : api.getJson("/api/v1/dashboards/builtins", 200)) {
            if ("spend_heatmap".equals(b.get("key").asText())) {
                heatmap = b;
            }
        }
        assertEquals("heatmap", heatmap.get("view").asText());
        assertEquals("spending", heatmap.get("category").asText());
        assertEquals("Daily spend", heatmap.get("subtitle").asText());
        assertEquals("Needs an account", heatmap.get("requires").asText());
        assertEquals("CHART", heatmap.get("templateType").asText());
        assertEquals(6, heatmap.get("params").get(0).get("defaultValue").asInt(), heatmap.toString());
    }

    @Test
    void componentBuiltinsHaveNoDataAndTemplateNonKpisHaveNoUnderlyingData() throws Exception {
        for (String key : List.of("card_utilisation", "portfolio_snapshot", "top_movers", "tax_harvest", "loan_payoff",
                "lending_balances", "account_tile", "emergency_fund", "shortcuts")) {
            assertEquals(400, api.post("/api/v1/dashboards/builtins/" + key + "/data", null).getStatus(), key);
        }
        for (String key : List.of("milestone_progress", "cap_headroom", "rewards_earned", "spend_heatmap", "allocation")) {
            assertEquals(400, api.post("/api/v1/dashboards/builtins/" + key + "/underlying", null).getStatus(), key);
        }
        assertEquals(400, api.post("/api/v1/dashboards/builtins/spend_heatmap/data",
                Map.of("params", Map.of("months", 13))).getStatus());
    }

    // ------------------------------------------------------------------ spend heatmap + allocation

    @Test
    void theSpendCalendarSumsDebitsPerDayLeavingOutExcludedTransfersAndCredits() throws Exception {
        UUID bank = bank(api, "Main", 10000, false);
        UUID savings = bank(api, "Savings", 0, false);
        debit(bank, today, "100");
        debit(bank, today, "50");
        txn(bank, today, "999", TransactionType.CREDIT, false);
        txn(bank, today.minusDays(1), "70", TransactionType.DEBIT, true);
        UUID out = debit(bank, today.minusDays(1), "30");
        UUID in = txn(savings, today.minusDays(1), "30", TransactionType.CREDIT, false);
        link("TRANSFER", out, in);
        debit(bank, today.minusDays(1), "20");
        debit(bank, today.minusMonths(2), "5");

        JsonNode oneMonth = builtinData("spend_heatmap", Map.of("months", 1));
        assertEquals("CHART", oneMonth.get("type").asText());
        BigDecimal total = BigDecimal.ZERO;
        int days = 0;
        for (JsonNode v : oneMonth.get("series").get(0).get("data")) {
            if (v.isNumber() && v.decimalValue().signum() != 0) {
                total = total.add(v.decimalValue());
                days++;
            }
        }
        assertEquals(0, new BigDecimal("170").compareTo(total), oneMonth.toString());
        assertEquals(2, days);

        JsonNode sixMonths = builtinData("spend_heatmap", null);
        BigDecimal all = BigDecimal.ZERO;
        for (JsonNode v : sixMonths.get("series").get(0).get("data")) {
            all = all.add(v.decimalValue());
        }
        assertEquals(0, new BigDecimal("175").compareTo(all));
    }

    @Test
    void allocationSumsOpenPositionsByAssetClassAndDrillsIntoPositions() throws Exception {
        UUID broker = broker();
        UUID stock = instrument("stock", "R2 Alloc Ltd");
        UUID fund = instrument("mutual_fund", "R2 Liquid Fund");
        api.patchJson("/api/v1/instruments/" + fund, "{\"assetClass\":\"DEBT\"}", 200);
        UUID sold = instrument("stock", "R2 Sold Ltd");
        trade(broker, stock, "buy", "10", "100", today.minusDays(20));
        trade(broker, fund, "buy", "100", "10", today.minusDays(20));
        trade(broker, sold, "buy", "5", "10", today.minusDays(20));
        trade(broker, sold, "sell", "5", "12", today.minusDays(2));
        price(stock, "120");
        price(fund, "11");

        JsonNode chart = builtinData("allocation", null);
        Map<String, BigDecimal> byClass = new HashMap<>();
        for (int i = 0; i < chart.get("categories").size(); i++) {
            byClass.put(chart.get("categories").get(i).asText(), chart.get("series").get(0).get("data").get(i).decimalValue());
        }
        assertEquals(2, byClass.size(), chart.toString());
        assertEquals(0, new BigDecimal("1200").compareTo(byClass.get("EQUITY")), chart.toString());
        assertEquals(0, new BigDecimal("1100").compareTo(byClass.get("DEBT")), chart.toString());

        JsonNode equity = adHocKpi("positions", "currentValue", List.of(filter("assetClass", "is", "EQUITY"),
                filter("isOpen", "is", true)));
        assertDecimal("1200", equity.get("value"));
        assertEquals("is Equity", chips(equity).get("assetClass"));
        JsonNode debt = adHocKpi("positions", "currentValue", List.of(filter("assetClass", "is", "DEBT")));
        assertDecimal("1100", debt.get("value"));
        assertEquals(1, debt.get("rowCount").asLong());
    }

    // ------------------------------------------------------------------ rewards

    private UUID rule(UUID card, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("accountId", card.toString(), "name", "Everything",
                "priority", 1, "accrualType", "PERCENT", "percentRate", 1, "rewardType", "CASH"));
        body.putAll(extra);
        return api.create("/api/v1/reward-rules", body);
    }

    private void milestone(UUID card, String name, int threshold) throws Exception {
        api.create("/api/v1/reward-milestones", Map.of("accountId", card.toString(), "name", name,
                "windowType", "CALENDAR_MONTH", "basis", "SPEND", "threshold", threshold, "payoutType", "INFO_TRACKER"));
    }

    @Test
    void rewardTemplatesShowCurrentWindowsAndCarryTheCardId() throws Exception {
        UUID cash = card("Cash Card");
        UUID points = card("Points Card");
        rule(cash, Map.of("periodCap", 50, "capWindow", "CALENDAR_MONTH"));
        rule(points, Map.of("accrualType", "SLAB", "rewardType", "POINTS", "slabSize", 100, "pointsPerSlab", 4,
                "percentRate", 0));
        jdbc.update("UPDATE accounts SET point_value_inr = 0.5 WHERE id = ?", points.toString());
        milestone(cash, "Big month", 50000);
        milestone(cash, "Small month", 5000);
        debit(cash, today, "10000");
        debit(points, today, "5000");

        // Milestones: today's window, not achieved, optional card.
        JsonNode milestones = builtinData("milestone_progress", null);
        assertEquals("TABLE", milestones.get("type").asText());
        assertEquals(1, milestones.get("rows").size(), milestones.toString());
        JsonNode big = milestones.get("rows").get(0);
        assertEquals("Big month", big.get("milestone").asText());
        assertEquals(cash.toString(), big.get("cardId").asText());
        assertDecimal("20", big.get("progressPct"));
        assertEquals(YearMonth.from(today).atEndOfMonth().toString(), big.get("windowEnd").asText());
        assertEquals(0, builtinData("milestone_progress", Map.of("accountId", points.toString())).get("rows").size());
        assertEquals(1, builtinData("milestone_progress", Map.of("accountId", cash.toString())).get("rows").size());

        // Caps: today's window, most used first.
        JsonNode caps = builtinData("cap_headroom", null);
        assertEquals(1, caps.get("rows").size(), caps.toString());
        JsonNode cap = caps.get("rows").get(0);
        assertEquals(cash.toString(), cap.get("cardId").asText());
        assertDecimal("50", cap.get("capLimit"));
        assertDecimal("50", cap.get("used"));
        assertDecimal("100", cap.get("utilizationPct"));
        assertEquals(0, builtinData("cap_headroom", Map.of("accountId", points.toString())).get("rows").size());

        // Rewards earned this FY: one row per card with its id, valued like the ad-hoc drill.
        JsonNode earned = builtinData("rewards_earned", null);
        assertEquals("aggregated", earned.get("mode").asText());
        Map<String, JsonNode> byCard = new HashMap<>();
        earned.get("rows").forEach(r -> byCard.put(r.get("ids").get("cardId").asText(), r.get("cells").get("")));
        assertEquals(2, byCard.size(), earned.toString());
        JsonNode cashCells = byCard.get(cash.toString());
        assertDecimal("50", cashCells.get("cashInr_sum"));
        assertDecimal("0", cashCells.get("points_sum"));
        assertDecimal("50", cashCells.get("valueInr_sum"));
        JsonNode pointsCells = byCard.get(points.toString());
        assertDecimal("200", pointsCells.get("points_sum"));
        assertDecimal("100", pointsCells.get("pointsValueInr_sum"));
        assertDecimal("100", pointsCells.get("valueInr_sum"));

        for (UUID c : List.of(cash, points)) {
            JsonNode drill = adHocKpi("reward_earnings", "valueInr", List.of(filter("effectiveDate", "current_fy", null),
                    filter("card", "is", c.toString())));
            assertSame(byCard.get(c.toString()).get("valueInr_sum"), drill.get("value"));
        }
        JsonNode drill = adHocKpi("reward_earnings", "valueInr", List.of(filter("effectiveDate", "current_fy", null),
                filter("card", "is", points.toString())));
        assertEquals("is Points Card", chips(drill).get("card"));
    }

    // ------------------------------------------------------------------ balance series

    private List<String> series(UUID accountId, String query) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode p : api.getJson("/api/v1/accounts/" + accountId + "/balance-series" + query, 200)) {
            out.add(p.get("date").asText() + "=" + p.get("balance").decimalValue().stripTrailingZeros().toPlainString());
        }
        return out;
    }

    @Test
    void theBalanceSeriesEndsOnTheShownBalanceAndWalksBackByDay() throws Exception {
        UUID bank = bank(api, "Main", 1000, false);
        debit(bank, today.minusDays(40), "10");
        debit(bank, today.minusDays(1), "200");
        txn(bank, today, "50", TransactionType.CREDIT, false);
        txn(bank, today.minusDays(2), "5", TransactionType.DEBIT, true);   // excluded still moves the balance
        debit(bank, today.plusDays(3), "100");                              // future-dated
        assertDecimal("735", api.getJson("/api/v1/accounts/" + bank, 200).get("balance"));

        List<String> points = series(bank, "");
        assertEquals(30, points.size());
        assertEquals(today.minusDays(29) + "=990", points.get(0));
        assertEquals(today.minusDays(3) + "=990", points.get(26));
        assertEquals(today.minusDays(2) + "=985", points.get(27));
        assertEquals(today.minusDays(1) + "=785", points.get(28));
        assertEquals(today + "=835", points.get(29));
        assertEquals(List.of(today.minusDays(1) + "=785", today + "=835"), series(bank, "?days=2"));
        assertEquals(365, series(bank, "?days=365").size());
        assertEquals(400, api.get("/api/v1/accounts/" + bank + "/balance-series?days=0").getStatus());
        assertEquals(400, api.get("/api/v1/accounts/" + bank + "/balance-series?days=366").getStatus());
    }

    @Test
    void anAnchoredCardWalksBackPastItsStatementByTransactions() throws Exception {
        UUID card = card("Anchored");
        debit(card, today.minusDays(15), "300");          // inside the statement
        statement(card, today.minusDays(10), "5000");      // owes 5,000 at the anchor
        debit(card, today.minusDays(5), "1000");
        assertDecimal("-6000", api.getJson("/api/v1/accounts/" + card, 200).get("balance"));

        List<String> points = series(card, "?days=20");
        assertEquals(today + "=-6000", points.get(19));
        assertEquals(today.minusDays(5) + "=-6000", points.get(14));
        assertEquals(today.minusDays(6) + "=-5000", points.get(13));
        assertEquals(today.minusDays(10) + "=-5000", points.get(9));
        assertEquals(today.minusDays(15) + "=-5000", points.get(4));
        assertEquals(today.minusDays(16) + "=-4700", points.get(3));
    }

    @Test
    void aBrokerHasNoSeriesAndSomeoneElsesAccountIs404() throws Exception {
        assertEquals(0, api.getJson("/api/v1/accounts/" + broker() + "/balance-series", 200).size());
        UUID theirs = bank(otherApi, "Theirs", 100, false);
        assertEquals(404, api.get("/api/v1/accounts/" + theirs + "/balance-series").getStatus());
        assertEquals(404, api.get("/api/v1/accounts/" + UUID.randomUUID() + "/balance-series").getStatus());
    }

    // ------------------------------------------------------------------ emergency fund

    @Test
    void theEmergencyFundCoversLiquidBalanceOverTheMedianOutflowAndItsDrillsAddUp() throws Exception {
        YearMonth current = YearMonth.from(today);
        UUID main = bank(api, "Main", 100000, false);
        UUID cash = wallet("Cash");
        UUID hidden = bank(api, "Hidden", 50000, true);
        UUID closed = bank(api, "Closed", 7000, false);
        jdbc.update("UPDATE accounts SET closed_on = ? WHERE id = ?", java.sql.Date.valueOf(today.minusDays(1)),
                closed.toString());
        UUID card = card("Card");

        LocalDate m1 = current.minusMonths(1).atDay(5);
        debit(main, m1, "1000");
        debit(cash, m1, "500");
        txn(main, m1, "700", TransactionType.DEBIT, true);                  // excluded
        UUID out = debit(main, m1, "2000");                                 // own transfer
        UUID in = txn(hidden, m1, "2000", TransactionType.CREDIT, false);
        link("TRANSFER", out, in);
        UUID reversed = debit(main, m1, "300");                             // reversed
        UUID reversal = txn(main, m1.plusDays(1), "300", TransactionType.CREDIT, false);
        link("REVERSAL", reversed, reversal);
        UUID billPaid = debit(main, m1, "4000");                            // card bill stays
        UUID billReceived = txn(card, m1, "4000", TransactionType.CREDIT, false);
        link("CC_PAYMENT", billPaid, billReceived);
        txn(main, m1, "9999", TransactionType.CREDIT, false);               // income
        debit(hidden, m1, "800");                                           // not liquid
        debit(closed, m1, "800");                                           // not liquid
        debit(card, m1, "600");                                             // not liquid
        debit(main, current.minusMonths(2).atDay(10), "3000");
        for (int i = 3; i <= 6; i++) {
            debit(main, current.minusMonths(i).atDay(1), "2000");
        }
        debit(main, current.minusMonths(7).atEndOfMonth(), "50000");        // before the six months
        debit(main, current.atDay(1), "999");                               // this month

        JsonNode fund = api.getJson("/api/v1/insights/emergency-fund", 200);

        List<String> ids = new ArrayList<>();
        fund.get("accounts").forEach(a -> ids.add(a.get("id").asText()));
        assertEquals(List.of(main.toString(), cash.toString()), ids);
        assertEquals("generic", fund.get("accounts").get(1).get("type").asText());
        // Main: 100,000 − 8,000 + 2×(…) — every transaction moves the balance, excluded and linked ones too.
        BigDecimal mainBalance = api.getJson("/api/v1/accounts/" + main, 200).get("balance").decimalValue();
        assertDecimal(mainBalance.toPlainString(), fund.get("accounts").get(0).get("balance"));
        assertDecimal("-500", fund.get("accounts").get(1).get("balance"));
        assertDecimal(mainBalance.subtract(new BigDecimal("500")).toPlainString(), fund.get("liquidBalance"));

        List<String> months = new ArrayList<>();
        fund.get("months").forEach(m -> months.add(m.get("month").asText() + "="
                + m.get("outflow").decimalValue().stripTrailingZeros().toPlainString()));
        assertEquals(List.of(current.minusMonths(6) + "=2000", current.minusMonths(5) + "=2000",
                current.minusMonths(4) + "=2000", current.minusMonths(3) + "=2000",
                current.minusMonths(2) + "=3000", current.minusMonths(1) + "=5500"), months);
        assertDecimal("2000", fund.get("medianOutflow"));
        BigDecimal covered = fund.get("liquidBalance").decimalValue().divide(new BigDecimal("2000"), 1,
                java.math.RoundingMode.HALF_UP);
        assertDecimal(covered.toPlainString(), fund.get("monthsCovered"));
        assertEquals(covered.compareTo(new BigDecimal("6")) >= 0 ? "high"
                : covered.compareTo(new BigDecimal("3")) >= 0 ? "medium" : "low", fund.get("band").asText());

        // The balance drill: net worth's bank + wallet/cash rows.
        JsonNode balanceDrill = adHocKpi("net_worth", "signedValue",
                List.of(filter("kind", "in", List.of("bank_account", "generic"))));
        assertSame(fund.get("liquidBalance"), balanceDrill.get("value"));
        assertEquals("in Bank account, Wallet/Cash", chips(balanceDrill).get("kind"));

        // Each month's drill: the ad-hoc transactions KPI over the same accounts and filters.
        for (JsonNode month : fund.get("months")) {
            YearMonth ym = YearMonth.parse(month.get("month").asText());
            JsonNode drill = adHocKpi("transactions", "spend", List.of(
                    filter("account", "in", ids),
                    filter("type", "is", "DEBIT"),
                    filter("isExcluded", "is", false),
                    filter("linkType", "not_in", List.of("TRANSFER", "REVERSAL")),
                    filter("date", "between", Map.of("from", ym.atDay(1).toString(), "to", ym.atEndOfMonth().toString()))));
            assertSame(month.get("outflow"), drill.get("value"));
            if (ym.equals(current.minusMonths(1))) {
                assertEquals(3, drill.get("rowCount").asLong());
                assertEquals("in Main, Cash", chips(drill).get("account"));
            }
        }
    }

    @Test
    void anEmergencyFundWithNoOutflowHasNoMonthsCovered() throws Exception {
        bank(api, "Quiet", 5000, false);
        JsonNode fund = api.getJson("/api/v1/insights/emergency-fund", 200);
        assertDecimal("5000", fund.get("liquidBalance"));
        assertEquals(6, fund.get("months").size());
        assertDecimal("0", fund.get("medianOutflow"));
        assertTrue(fund.get("monthsCovered").isNull());
        assertTrue(fund.get("band").isNull());

        JsonNode none = otherApi.getJson("/api/v1/insights/emergency-fund", 200);
        assertEquals(0, none.get("accounts").size());
        assertDecimal("0", none.get("liquidBalance"));
    }

    private static List<String> beforeHistory(JsonNode fund) {
        List<String> out = new ArrayList<>();
        fund.get("months").forEach(m -> out.add(m.get("month").asText() + "=" + m.get("beforeHistory").asBoolean()));
        return out;
    }

    @Test
    void aNewUsersMedianRunsOverOnlyTheMonthsSinceTheirFirstTransaction() throws Exception {
        YearMonth current = YearMonth.from(today);
        UUID main = bank(api, "New Main", 60000, false);
        debit(main, current.minusMonths(2).atDay(3), "3000");
        debit(main, current.minusMonths(1).atDay(3), "5000");
        debit(main, current.atDay(1), "999");                               // this month: not a full month

        JsonNode fund = api.getJson("/api/v1/insights/emergency-fund", 200);

        assertEquals(List.of(current.minusMonths(6) + "=true", current.minusMonths(5) + "=true",
                current.minusMonths(4) + "=true", current.minusMonths(3) + "=true",
                current.minusMonths(2) + "=false", current.minusMonths(1) + "=false"), beforeHistory(fund));
        assertEquals(2, fund.get("historyMonths").asInt());
        assertDecimal("0", fund.get("months").get(0).get("outflow"));
        assertDecimal("4000", fund.get("medianOutflow"));
        BigDecimal covered = fund.get("liquidBalance").decimalValue().divide(new BigDecimal("4000"), 1,
                java.math.RoundingMode.HALF_UP);
        assertDecimal(covered.toPlainString(), fund.get("monthsCovered"));
        assertTrue(fund.get("band").isTextual());
    }

    @Test
    void aQuietMonthInsideHistoryStillCountsAsZero() throws Exception {
        YearMonth current = YearMonth.from(today);
        UUID main = bank(api, "Gap Main", 90000, false);
        UUID cash = wallet("Gap Cash");
        // History starts with a credit on the wallet four months back; its month has no outflow.
        txn(cash, current.minusMonths(4).atDay(2), "10000", TransactionType.CREDIT, false);
        debit(main, current.minusMonths(3).atDay(2), "1000");
        // two months back: nothing at all
        debit(main, current.minusMonths(1).atDay(2), "6000");

        JsonNode fund = api.getJson("/api/v1/insights/emergency-fund", 200);

        assertEquals(List.of(current.minusMonths(6) + "=true", current.minusMonths(5) + "=true",
                current.minusMonths(4) + "=false", current.minusMonths(3) + "=false",
                current.minusMonths(2) + "=false", current.minusMonths(1) + "=false"), beforeHistory(fund));
        assertEquals(4, fund.get("historyMonths").asInt());
        // In-history outflows 0, 1,000, 0, 6,000 → median (0 + 1,000) / 2.
        assertDecimal("500", fund.get("medianOutflow"));
        BigDecimal covered = fund.get("liquidBalance").decimalValue().divide(new BigDecimal("500"), 1,
                java.math.RoundingMode.HALF_UP);
        assertDecimal(covered.toPlainString(), fund.get("monthsCovered"));
    }

    @Test
    void historyCountsExcludedTransactionsButNotOtherAccounts() throws Exception {
        YearMonth current = YearMonth.from(today);
        UUID main = bank(api, "Hist Main", 20000, false);
        UUID hidden = bank(api, "Hist Hidden", 5000, true);
        debit(hidden, current.minusMonths(6).atDay(1), "100");              // not liquid: no history
        txn(main, current.minusMonths(3).atDay(1), "100", TransactionType.DEBIT, true);  // excluded: starts history
        debit(main, current.minusMonths(1).atDay(1), "2000");

        JsonNode fund = api.getJson("/api/v1/insights/emergency-fund", 200);

        assertEquals(3, fund.get("historyMonths").asInt());
        assertEquals(current.minusMonths(4) + "=true", beforeHistory(fund).get(2));
        assertEquals(current.minusMonths(3) + "=false", beforeHistory(fund).get(3));
        // In-history outflows 0 (excluded debit left out), 0, 2,000 → median 0 → no months covered.
        assertDecimal("0", fund.get("medianOutflow"));
        assertTrue(fund.get("monthsCovered").isNull());
        assertTrue(fund.get("band").isNull());
    }

    @Test
    void aUserWithNoTransactionsHasNoHistoryAndNoMonthsCovered() throws Exception {
        bank(api, "Empty Main", 40000, false);
        JsonNode fund = api.getJson("/api/v1/insights/emergency-fund", 200);
        assertEquals(6, fund.get("months").size());
        fund.get("months").forEach(m -> assertTrue(m.get("beforeHistory").asBoolean()));
        assertEquals(0, fund.get("historyMonths").asInt());
        assertDecimal("0", fund.get("medianOutflow"));
        assertTrue(fund.get("monthsCovered").isNull());
        assertTrue(fund.get("band").isNull());

        JsonNode noAccounts = otherApi.getJson("/api/v1/insights/emergency-fund", 200);
        assertEquals(0, noAccounts.get("historyMonths").asInt());
        noAccounts.get("months").forEach(m -> assertTrue(m.get("beforeHistory").asBoolean()));
        assertTrue(noAccounts.get("monthsCovered").isNull());
    }

    @Test
    void aFirstTransactionThisMonthLeavesNoFullMonthInHistory() throws Exception {
        UUID main = bank(api, "Fresh Main", 40000, false);
        debit(main, YearMonth.from(today).atDay(1), "1234");
        JsonNode fund = api.getJson("/api/v1/insights/emergency-fund", 200);
        assertEquals(0, fund.get("historyMonths").asInt());
        fund.get("months").forEach(m -> assertTrue(m.get("beforeHistory").asBoolean()));
        assertTrue(fund.get("monthsCovered").isNull());
    }

    @Test
    void theRealisedBlockShowsOtherClassGainsApartFromEquity() throws Exception {
        UUID broker = broker();
        UUID stock = instrument("stock", "R2 Other Eq Ltd");
        UUID gold = instrument("etf", "R2 GOLD BEES");
        trade(broker, stock, "buy", "10", "100", today.minusDays(30));
        trade(broker, stock, "sell", "10", "80", today);                    // equity short-term loss 200
        trade(broker, gold, "buy", "10", "50", today.minusDays(800));
        trade(broker, gold, "sell", "4", "70", today);                      // other long-term gain 80
        trade(broker, gold, "buy", "10", "60", today.minusDays(100));
        price(gold, "55");

        JsonNode r = api.getJson("/api/v1/investments/tax/harvest", 200).get("realised");
        assertDecimal("200", r.get("stcl"));
        assertDecimal("0", r.get("ltcg"));
        // Pooled set-off: the equity short-term loss absorbs the gold long-term gain; 120 carries forward.
        assertDecimal("0", r.get("netLtcg"));
        assertDecimal("120", r.get("stclCarriedForward"));
        JsonNode other = r.get("otherGains");
        assertDecimal("0", other.get("shortTerm"));
        assertDecimal("80", other.get("longTerm"));
        assertDecimal("80", other.get("total"));
    }

    // ------------------------------------------------------------------ tax harvest

    @Test
    void taxHarvestBooksThisYearsGainsAndRanksTheOpenLots() throws Exception {
        UUID broker = broker();
        UUID winner = instrument("stock", "R2 Winner Ltd");
        UUID loser = instrument("stock", "R2 Loser Ltd");
        trade(broker, winner, "buy", "10", "100", today.minusDays(400));
        trade(broker, winner, "buy", "10", "150", today.minusDays(350));
        trade(broker, winner, "sell", "5", "300", today);                  // long-term gain 1,000
        trade(broker, loser, "buy", "10", "500", today.minusDays(30));
        trade(broker, loser, "sell", "4", "400", today);                   // short-term loss 400
        price(winner, "200");
        price(loser, "450");

        JsonNode h = api.getJson("/api/v1/investments/tax/harvest", 200);
        int fy = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        assertEquals(fy, h.get("fy").asInt());
        assertEquals(LocalDate.of(fy, 4, 1).toString(), h.get("fyStart").asText());

        JsonNode r = h.get("realised");
        assertDecimal("0", r.get("stcg"));
        assertDecimal("400", r.get("stcl"));
        assertDecimal("1000", r.get("ltcg"));
        assertDecimal("0", r.get("ltcl"));
        assertDecimal("0", r.get("netStcg"));
        assertDecimal("600", r.get("netLtcg"));
        assertDecimal("125000", r.get("exemptionLimit"));
        assertDecimal("600", r.get("exemptionUsed"));
        assertDecimal("124400", r.get("exemptionLeft"));
        assertDecimal("0", r.get("slabGains"));

        JsonNode s = h.get("summary");
        assertDecimal("500", s.get("harvestableLtcg"));
        assertEquals(1, s.get("turningLongTermSoon").get("count").asInt());
        assertDecimal("500", s.get("turningLongTermSoon").get("gain"));
        assertDecimal("-300", s.get("harvestableLosses").get("shortTerm"));
        assertDecimal("0", s.get("harvestableLosses").get("longTerm"));

        JsonNode lots = h.get("openLots");
        assertEquals(3, lots.get("totalElements").asInt());
        JsonNode first = lots.get("items").get(0);
        JsonNode second = lots.get("items").get(1);
        JsonNode last = lots.get("items").get(2);
        assertDecimal("500", first.get("gain"));
        assertDecimal("500", second.get("gain"));
        // Ties keep the older lot first: the long-term remainder of the first buy.
        assertEquals("long", first.get("term").asText());
        assertDecimal("5", first.get("quantity"));
        assertEquals(0, first.get("daysToLongTerm").asInt());
        assertEquals("short", second.get("term").asText());
        assertEquals(today.minusDays(350).plusDays(366).toString(), second.get("longTermOn").asText());
        assertEquals(16, second.get("daysToLongTerm").asInt());
        assertEquals("R2 Loser Ltd", last.get("instrument").asText());
        assertEquals("R2 Broker", last.get("broker").asText());
        assertEquals("EQUITY", last.get("assetClass").asText());
        assertEquals("EQUITY_ORIENTED", last.get("taxClass").asText());
        assertDecimal("-300", last.get("gain"));
        assertFalse(last.get("grandfathered").asBoolean());
        JsonNode holding = null;
        for (JsonNode p : api.getJson("/api/v1/investments/positions", 200).get("positions")) {
            if (p.get("instrument").get("id").asText().equals(loser.toString())) {
                holding = p;
            }
        }
        assertEquals(holding.get("holdingId").asText(), last.get("holdingId").asText());

        assertEquals(1, api.getJson("/api/v1/investments/tax/harvest?page=1&size=2", 200).get("openLots").get("items").size());
        JsonNode lastYear = api.getJson("/api/v1/investments/tax/harvest?fy=" + (fy - 1), 200);
        assertDecimal("0", lastYear.get("realised").get("ltcg"));
        assertEquals(400, api.get("/api/v1/investments/tax/harvest?fy=1990").getStatus());

        // The booked-gains drill: realised lots this FY by term and tax class.
        JsonNode longDrill = adHocKpi("realized_lots", "realizedPnl", List.of(filter("sellDate", "current_fy", null),
                filter("term", "is", "long"), filter("taxClass", "is", "EQUITY_ORIENTED")));
        assertDecimal("1000", longDrill.get("value"));
        JsonNode shortDrill = adHocKpi("realized_lots", "realizedPnl", List.of(filter("sellDate", "current_fy", null),
                filter("term", "in", List.of("short")), filter("taxClass", "is", "EQUITY_ORIENTED")));
        assertDecimal("-400", shortDrill.get("value"));
        assertEquals("is Equity-oriented", chips(shortDrill).get("taxClass"));
    }
}
