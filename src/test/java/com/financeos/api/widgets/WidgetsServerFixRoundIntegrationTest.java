package com.financeos.api.widgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
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
 * Against real persistence (H2), the review round's fixes: asset-class overrides are per user (one
 * user's PATCH never moves another user's class), account create/update answers carry the live
 * utilisation, the emergency fund's history starts with a full month, and a past year's tax harvest
 * has no open-lot summary.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WidgetsServerFixRoundIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient alice;
    private ApiTestClient bob;
    private UUID aliceId;
    private UUID bobId;
    private final List<UUID> instrumentIds = new ArrayList<>();
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String a = "fix-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        String b = "fix-b-" + UUID.randomUUID() + "@example.test";
        bob = ApiTestClient.signUp(mockMvc, mapper, b);
        bobId = userRepository.findByEmail(b).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId, bobId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual != null && actual.isNumber(), "expected " + expected + " but was " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static UUID broker(ApiTestClient client) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "broker", "name", "Fix Broker", "provider", "Zerodha",
                "clientId", "FX1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static void buy(ApiTestClient client, UUID broker, UUID instrument, LocalDate date) throws Exception {
        client.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", "buy", "quantity", "10", "price", "10",
                "tradeDate", date.toString()));
    }

    private static JsonNode position(ApiTestClient client, UUID instrument) throws Exception {
        for (JsonNode p : client.getJson("/api/v1/investments/positions", 200).get("positions")) {
            if (p.get("instrument").get("id").asText().equals(instrument.toString())) {
                return p;
            }
        }
        throw new AssertionError("no position for " + instrument);
    }

    private static JsonNode harvestLot(ApiTestClient client, UUID instrument) throws Exception {
        for (JsonNode l : client.getJson("/api/v1/investments/tax/harvest", 200).get("openLots").get("items")) {
            if (l.get("instrumentId").asText().equals(instrument.toString())) {
                return l;
            }
        }
        throw new AssertionError("no open lot for " + instrument);
    }

    // ------------------------------------------------------------------ per-user asset-class override

    @Test
    void anAssetClassOverrideIsTheCallersOnly() throws Exception {
        UUID fund = alice.create("/api/v1/instruments", Map.of("type", "mutual_fund", "name",
                "Fix Round Liquid Fund " + UUID.randomUUID().toString().substring(0, 6)));
        instrumentIds.add(fund);
        buy(alice, broker(alice), fund, today.minusDays(40));
        buy(bob, broker(bob), fund, today.minusDays(40));
        assertEquals("DEBT", bob.getJson("/api/v1/instruments/" + fund, 200).get("assetClass").asText());

        JsonNode patched = alice.patchJson("/api/v1/instruments/" + fund, "{\"assetClass\":\"GOLD\"}", 200);
        assertEquals("GOLD", patched.get("assetClass").asText());
        assertEquals("MANUAL", patched.get("assetClassSource").asText());
        assertEquals("OTHER", patched.get("taxClass").asText());

        // Alice sees her override everywhere the class is read …
        assertEquals("GOLD", alice.getJson("/api/v1/instruments/" + fund, 200).get("assetClass").asText());
        assertEquals("GOLD", position(alice, fund).get("assetClass").asText());
        assertEquals("OTHER", position(alice, fund).get("taxClass").asText());
        assertEquals("GOLD", harvestLot(alice, fund).get("assetClass").asText());
        // … and Bob, holding the same instrument, sees the global class untouched.
        JsonNode bobsView = bob.getJson("/api/v1/instruments/" + fund, 200);
        assertEquals("DEBT", bobsView.get("assetClass").asText());
        assertFalse("MANUAL".equals(bobsView.get("assetClassSource").asText()));
        assertEquals("SPECIFIED_DEBT", bobsView.get("taxClass").asText());
        assertEquals("DEBT", position(bob, fund).get("assetClass").asText());
        assertEquals("DEBT", harvestLot(bob, fund).get("assetClass").asText());
        // The shared instrument row was never written as MANUAL.
        Integer manualRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM instruments WHERE id = ? AND asset_class_source = 'MANUAL'", Integer.class,
                fund.toString());
        assertEquals(0, manualRows);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_instrument_overrides WHERE user_id = ?",
                Integer.class, aliceId.toString()));

        // Bob's own override does not touch Alice's.
        bob.patchJson("/api/v1/instruments/" + fund, "{\"assetClass\":\"EQUITY\"}", 200);
        assertEquals("GOLD", alice.getJson("/api/v1/instruments/" + fund, 200).get("assetClass").asText());
        assertEquals("EQUITY", position(bob, fund).get("assetClass").asText());

        // Null removes the caller's override only.
        JsonNode cleared = alice.patchJson("/api/v1/instruments/" + fund, "{\"assetClass\":null}", 200);
        assertEquals("DEBT", cleared.get("assetClass").asText());
        assertEquals("DEBT", position(alice, fund).get("assetClass").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_instrument_overrides WHERE user_id = ?",
                Integer.class, aliceId.toString()));
        assertEquals("EQUITY", bob.getJson("/api/v1/instruments/" + fund, 200).get("assetClass").asText());
    }

    @Test
    void patchingAnUnknownInstrumentIs404() throws Exception {
        alice.patchJson("/api/v1/instruments/" + UUID.randomUUID(), "{\"assetClass\":\"GOLD\"}", 404);
    }

    // ------------------------------------------------------------------ account write responses

    @Test
    void creatingAndUpdatingACardAnswerWithItsLiveUtilisation() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", "credit_card", "name", "Fix Card",
                "last4", "4321", "creditLimit", 100000, "anniversaryDate", today.minusMonths(3).toString(),
                "financialPosition", "liability", "excludeFromNetAsset", false));
        JsonNode created = alice.postJson("/api/v1/accounts", body, 201);
        assertDecimal("0.0", created.get("utilizationPct"));
        UUID id = UUID.fromString(created.get("id").asText());

        Account account = accountRepository.findById(id).orElseThrow();
        Transaction spend = new Transaction(account, today, new BigDecimal("25000"), "Fix spend",
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        spend.setUser(account.getUser());
        transactionRepository.save(spend);

        body.put("creditLimit", 50000);
        JsonNode updated = alice.putJson("/api/v1/accounts/" + id, body, 200);
        assertDecimal("50.0", updated.get("utilizationPct"));
        assertDecimal("50.0", alice.getJson("/api/v1/accounts/" + id, 200).get("utilizationPct"));
    }

    // ------------------------------------------------------------------ emergency fund

    @Test
    void aMidMonthFirstTransactionStartsHistoryTheMonthAfter() throws Exception {
        YearMonth current = YearMonth.from(today);
        UUID bank = alice.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Fix Bank",
                "last4", "1111", "openingBalance", 50000, "financialPosition", "asset", "excludeFromNetAsset", false));
        Account account = accountRepository.findById(bank).orElseThrow();
        for (LocalDate day : List.of(current.minusMonths(3).atDay(15), current.minusMonths(2).atDay(5),
                current.minusMonths(1).atDay(5))) {
            Transaction t = new Transaction(account, day, new BigDecimal("1000"), "Fix out", TransactionSource.manual,
                    TransactionType.DEBIT, false, false);
            t.setUser(account.getUser());
            transactionRepository.save(t);
        }

        JsonNode fund = alice.getJson("/api/v1/insights/emergency-fund", 200);

        Map<String, Boolean> before = new HashMap<>();
        fund.get("months").forEach(m -> before.put(m.get("month").asText(), m.get("beforeHistory").asBoolean()));
        assertTrue(before.get(current.minusMonths(3).toString()), "joined on the 15th: a partial month");
        assertFalse(before.get(current.minusMonths(2).toString()));
        assertEquals(2, fund.get("historyMonths").asInt());
        assertDecimal("1000", fund.get("medianOutflow"));
    }

    // ------------------------------------------------------------------ tax harvest, past year

    @Test
    void aPastYearsHarvestHasNoOpenLotSummary() throws Exception {
        UUID stock = alice.create("/api/v1/instruments", Map.of("type", "stock", "name", "Fix Past Ltd",
                "symbol", "FXP" + UUID.randomUUID().toString().substring(0, 5).toUpperCase(), "exchange", "NSE"));
        instrumentIds.add(stock);
        buy(alice, broker(alice), stock, today.minusYears(3));
        int currentFy = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;

        JsonNode past = alice.getJson("/api/v1/investments/tax/harvest?fy=" + (currentFy - 1), 200);
        assertTrue(past.get("summary").isNull());
        assertEquals(0, past.get("openLots").get("totalElements").asInt());
        JsonNode current = alice.getJson("/api/v1/investments/tax/harvest", 200);
        assertFalse(current.get("summary").isNull());
        assertEquals(1, current.get("openLots").get("totalElements").asInt());
        assertDecimal("125000", current.get("realised").get("exemptionLimit"));
    }
}
