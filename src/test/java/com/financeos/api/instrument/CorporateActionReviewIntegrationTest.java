package com.financeos.api.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

/** The Corporate actions page list: pending and automatic suggestions with evidence, not-trading stocks, per user. */
@SpringBootTest
@AutoConfigureMockMvc
class CorporateActionReviewIntegrationTest {

    private static final String PATH = "/api/v1/corporate-action-suggestions";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient alice;
    private ApiTestClient bob;
    private UUID aliceId;
    private UUID bobId;
    private final List<UUID> instrumentIds = new ArrayList<>();
    private LocalDate today;
    private String tag;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String a = "car-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        String b = "car-b-" + UUID.randomUUID() + "@example.test";
        bob = ApiTestClient.signUp(mockMvc, mapper, b);
        bobId = userRepository.findByEmail(b).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId, bobId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    private UUID stock(String name, String symbol, String isin) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "stock");
        body.put("name", name + " " + tag);
        body.put("symbol", symbol + tag.substring(0, 4));
        body.put("exchange", "NSE");
        body.put("isin", isin);
        UUID id = alice.create("/api/v1/instruments", body);
        instrumentIds.add(id);
        return id;
    }

    private static UUID broker(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "broker", "name", name, "provider", "Zerodha",
                "clientId", "CAR1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static void trade(ApiTestClient client, UUID broker, UUID instrument, String type, String qty, String price,
                              LocalDate date) throws Exception {
        client.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", qty, "price", price,
                "tradeDate", date.toString()));
    }

    private void suggestion(UUID user, UUID instrument, String kind, LocalDate ex, Integer from, Integer to, String status,
                            String reason, String feedFactor) {
        jdbc.update("INSERT INTO corporate_action_suggestions (id, user_id, instrument_id, kind, ex_date, ratio_from, ratio_to, "
                        + "subject, status, reason, created_at, feed_factor) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), user.toString(), instrument.toString(), kind, java.sql.Date.valueOf(ex), from, to,
                "Notice text", status, reason, Timestamp.from(Instant.now()),
                feedFactor == null ? null : new BigDecimal(feedFactor));
    }

    private static void assertDecimal(String expected, JsonNode actual, String why) {
        assertTrue(actual != null && actual.isNumber(), why + ": expected " + expected + " but was " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), why + ": " + expected + " vs " + actual);
    }

    private static JsonNode only(JsonNode list, String instrumentId) {
        JsonNode found = null;
        for (JsonNode n : list) {
            if (n.get("instrument").get("id").asText().equals(instrumentId)) {
                assertTrue(found == null, "one item for " + instrumentId);
                found = n;
            }
        }
        assertTrue(found != null, "an item for " + instrumentId + " in " + list);
        return found;
    }

    @Test
    void pendingItemCarriesSharesFactorsAndAverageCost() throws Exception {
        UUID s = stock("Bonus Co", "BNC", "INE" + tag + "01");
        UUID br = broker(alice, "A");
        trade(alice, br, s, "buy", "10", "100", today.minusDays(100));
        trade(alice, br, s, "buy", "10", "200", today.minusDays(60));
        trade(alice, br, s, "buy", "5", "300", today.minusDays(5)); // after the ex-date: not counted
        suggestion(aliceId, s, "bonus", today.minusDays(30), 1, 2, "pending", "YAHOO_MISMATCH", "1.5");

        JsonNode item = only(alice.getJson(PATH, 200).get("needsReview"), s.toString());

        assertEquals("bonus", item.get("kind").asText());
        assertEquals("NSE", item.get("source").asText());
        assertEquals("nse", item.get("instrument").get("exchange").asText().toLowerCase(Locale.ROOT));
        assertDecimal("1.5", item.get("feedFactor"), "feed factor");
        assertDecimal("2", item.get("exchangeFactor"), "exchange factor");
        assertDecimal("20", item.get("sharesBefore"), "shares before");
        assertDecimal("40", item.get("sharesAfter"), "shares after");
        assertDecimal("150", item.get("avgCostBefore"), "avg cost before");
        assertDecimal("75", item.get("avgCostAfter"), "avg cost after");
        assertTrue(item.get("soldQty").isNull());
        assertTrue(item.get("createdAt").isTextual());
    }

    @Test
    void autoAppliedItemIsListedSeparatelyNewestFirstWithoutFeedFactorWhenNotConsulted() throws Exception {
        UUID s = stock("Split Co", "SPC", "INE" + tag + "02");
        UUID t = stock("Later Co", "LTC", "INE" + tag + "03");
        UUID br = broker(alice, "A");
        trade(alice, br, s, "buy", "10", "100", today.minusDays(100));
        trade(alice, br, t, "buy", "10", "100", today.minusDays(100));
        suggestion(aliceId, s, "split", today.minusDays(50), 1, 5, "auto_applied", null, "5");
        suggestion(aliceId, t, "split", today.minusDays(20), 1, 2, "auto_applied", null, null);

        JsonNode body = alice.getJson(PATH, 200);
        JsonNode applied = body.get("appliedAutomatically");

        assertEquals(0, body.get("needsReview").size());
        assertEquals(2, applied.size());
        assertEquals(t.toString(), applied.get(0).get("instrument").get("id").asText());
        JsonNode split = only(applied, s.toString());
        assertDecimal("5", split.get("feedFactor"), "feed factor");
        assertDecimal("5", split.get("exchangeFactor"), "exchange factor");
        assertDecimal("50", split.get("sharesAfter"), "shares after");
        assertDecimal("20", split.get("avgCostAfter"), "avg cost after");
        assertTrue(only(applied, t.toString()).get("feedFactor").isNull());
    }

    @Test
    void oversoldItemCarriesSoldQuantityAndDate() throws Exception {
        UUID s = stock("Short Co", "SHC", "INE" + tag + "04");
        UUID br = broker(alice, "A");
        trade(alice, br, s, "buy", "10", "100", today.minusDays(100));
        LocalDate sellDay = today.minusDays(40);
        trade(alice, br, s, "buy", "5", "100", today.minusDays(30)); // bought after the sell: the sell is 5 short on its day
        trade(alice, br, s, "sell", "15", "120", sellDay);
        suggestion(aliceId, s, "oversold", sellDay, null, null, "pending", null, null);

        JsonNode item = only(alice.getJson(PATH, 200).get("needsReview"), s.toString());

        assertEquals("oversold", item.get("kind").asText());
        assertTrue(item.get("source").isNull());
        assertTrue(item.get("exchangeFactor").isNull());
        assertTrue(item.get("sharesAfter").isNull());
        assertDecimal("10", item.get("sharesBefore"), "shares before");
        assertDecimal("100", item.get("avgCostBefore"), "avg cost before");
        assertTrue(item.get("avgCostAfter").isNull());
        assertDecimal("5", item.get("soldQty"), "sold qty");
        assertEquals(sellDay.toString(), item.get("sellDate").asText());
    }

    @Test
    void notTradingStockIsListedUntilAnsweredByAMerger() throws Exception {
        UUID s = stock("Gone Co", "GNC", "INE" + tag + "05");
        UUID acquirer = stock("Buyer Co", "BYC", "INE" + tag + "06");
        trade(alice, broker(alice, "A"), s, "buy", "8", "50", today.minusDays(100));
        alice.postJson("/api/v1/instruments/" + s + "/price", Map.of("price", "42.5", "asOf", today.minusDays(3).toString()), 200);
        jdbc.update("UPDATE instruments SET price_status = 'not_trading', price_status_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), s.toString());

        JsonNode listed = only(alice.getJson(PATH, 200).get("notTrading"), s.toString());
        assertDecimal("8", listed.get("sharesHeld"), "shares held");
        assertDecimal("42.5", listed.get("lastPrice"), "last price");
        assertEquals(today.minusDays(3).toString(), listed.get("lastPriceDate").asText());
        assertTrue(listed.get("markedAt").isTextual());

        alice.create("/api/v1/instruments/" + s + "/corporate-actions", Map.of("type", "merger", "ratioFrom", 1, "ratioTo", 1,
                "exDate", today.minusDays(1).toString(), "targetInstrumentId", acquirer.toString()));
        assertEquals(0, alice.getJson(PATH, 200).get("notTrading").size());
    }

    @Test
    void otherUsersRowsAreNotVisible() throws Exception {
        UUID s = stock("Shared Co", "SHD", "INE" + tag + "07");
        trade(alice, broker(alice, "A"), s, "buy", "10", "100", today.minusDays(100));
        suggestion(bobId, s, "bonus", today.minusDays(30), 1, 2, "pending", "YAHOO_MISSING", null);
        suggestion(bobId, s, "split", today.minusDays(20), 1, 2, "auto_applied", null, null);

        JsonNode body = alice.getJson(PATH, 200);

        assertEquals(0, body.get("needsReview").size());
        assertEquals(0, body.get("appliedAutomatically").size());
        JsonNode his = bob.getJson(PATH, 200);
        assertEquals(1, his.get("needsReview").size());
        assertEquals(1, his.get("appliedAutomatically").size());
    }
}
