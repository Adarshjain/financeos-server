package com.financeos.api.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /instruments with the stale / held filters and the notHeld / edited flags, and the paged price
 * history, against real persistence (H2).
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstrumentCatalogListIntegrationTest {

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
        String a = "icl-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        String b = "icl-b-" + UUID.randomUUID() + "@example.test";
        bob = ApiTestClient.signUp(mockMvc, mapper, b);
        bobId = userRepository.findByEmail(b).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId, bobId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    // ------------------------------------------------------------------ helpers

    private UUID stock(String name, String isinSuffix) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "stock");
        body.put("name", name + " " + tag);
        body.put("symbol", name.substring(0, 3).toUpperCase(Locale.ROOT) + tag.substring(0, 4));
        body.put("exchange", "NSE");
        body.put("isin", "INE" + tag + isinSuffix);
        UUID id = alice.create("/api/v1/instruments", body);
        instrumentIds.add(id);
        return id;
    }

    private void feedPrice(UUID instrument, LocalDate asOf, String close) {
        jdbc.update("INSERT INTO instrument_prices (id, instrument_id, as_of, close, source, created_at) "
                        + "VALUES (?, ?, ?, ?, 'YAHOO', CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), instrument.toString(), Date.valueOf(asOf), new BigDecimal(close));
    }

    private static UUID broker(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "broker", "name", name, "provider", "Zerodha",
                "clientId", "ICL1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static void trade(ApiTestClient client, UUID broker, UUID instrument, String type, String qty, String price)
            throws Exception {
        client.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", qty, "price", price,
                "tradeDate", AppTime.today().minusDays(30).toString()));
    }

    private JsonNode list(ApiTestClient client, String query) throws Exception {
        return client.getJson("/api/v1/instruments?search=" + tag + query, 200);
    }

    private static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(n.get("name").asText().replace(" ", "|").split("\\|")[0]));
        return out;
    }

    private static JsonNode item(JsonNode page, String name) {
        for (JsonNode n : page.get("items")) {
            if (n.get("name").asText().startsWith(name + " ")) {
                return n;
            }
        }
        throw new AssertionError("no item " + name + " in " + page);
    }

    /**
     * Fresh (price today, held), Edge (price 4 days old, not held), Old (price 5 days old, held in one of
     * two brokers), None (no price, bought and sold out).
     */
    private void catalog() throws Exception {
        UUID fresh = stock("Fresh", "01");
        UUID edge = stock("Edge", "02");
        UUID old = stock("Old", "03");
        UUID none = stock("None", "04");
        feedPrice(fresh, today, "100");
        feedPrice(edge, today.minusDays(4), "100");
        feedPrice(old, today.minusDays(5), "100");
        feedPrice(old, today.minusDays(9), "99");
        UUID b1 = broker(alice, "L1");
        UUID b2 = broker(alice, "L2");
        trade(alice, b1, fresh, "buy", "10", "100");
        trade(alice, b1, old, "buy", "10", "100");
        trade(alice, b2, old, "buy", "10", "100");
        trade(alice, b1, old, "sell", "10", "100");
        trade(alice, b1, none, "buy", "5", "100");
        trade(alice, b1, none, "sell", "5", "100");
    }

    // ------------------------------------------------------------------ list

    @Test
    void itemsCarryExchangeLastPriceDateAndNotHeldAcrossBrokers() throws Exception {
        catalog();
        JsonNode page = list(alice, "");
        assertEquals(4, page.get("totalElements").asInt());
        assertEquals("NSE", item(page, "Fresh").get("exchange").asText());
        assertEquals(today.toString(), item(page, "Fresh").get("lastPriceAsOf").asText());
        assertEquals(today.minusDays(5).toString(), item(page, "Old").get("lastPriceAsOf").asText());
        assertTrue(item(page, "None").get("lastPriceAsOf").isNull());
        assertFalse(item(page, "Fresh").get("notHeld").asBoolean());
        assertTrue(item(page, "Edge").get("notHeld").asBoolean(), "never bought");
        assertFalse(item(page, "Old").get("notHeld").asBoolean(), "still open at broker 2 though sold out at broker 1");
        assertTrue(item(page, "None").get("notHeld").asBoolean(), "bought and sold out");

        JsonNode bobs = list(bob, "");
        for (JsonNode n : bobs.get("items")) {
            assertTrue(n.get("notHeld").asBoolean(), "Bob holds none of them");
        }
    }

    @Test
    void staleKeepsPricesOlderThanFourDaysAndInstrumentsWithNone() throws Exception {
        catalog();
        assertEquals(List.of("None", "Old"), names(list(alice, "&stale=true")));
        assertEquals(List.of("Edge", "Fresh"), names(list(alice, "&stale=false")), "4 days old is still current");
        assertEquals(2, list(alice, "&stale=true").get("totalElements").asInt());
        assertEquals(4, list(alice, "").get("totalElements").asInt());
    }

    @Test
    void aManualPriceOfTheUsersOwnMakesAnInstrumentCurrentForThemOnly() throws Exception {
        catalog();
        UUID old = instrumentIds.get(2);
        alice.postJson("/api/v1/instruments/" + old + "/price",
                Map.of("price", "101", "asOf", today.toString()), 200);
        assertEquals(List.of("None"), names(list(alice, "&stale=true")));
        assertEquals(List.of("None", "Old"), names(list(bob, "&stale=true")));
    }

    @Test
    void heldFiltersByOpenQuantityAndCombinesWithStale() throws Exception {
        catalog();
        assertEquals(List.of("Fresh", "Old"), names(list(alice, "&held=true")));
        assertEquals(List.of("Edge", "None"), names(list(alice, "&held=false")));
        assertEquals(List.of("Old"), names(list(alice, "&held=true&stale=true")));
        assertEquals(List.of("Fresh"), names(list(alice, "&held=true&stale=false")));
        assertEquals(List.of("None"), names(list(alice, "&held=false&stale=true")));
        assertEquals(0, list(bob, "&held=true").get("totalElements").asInt());
        assertEquals(4, list(bob, "&held=false").get("totalElements").asInt());
    }

    @Test
    void filtersPageAndSortByName() throws Exception {
        catalog();
        assertEquals(List.of("Edge", "Fresh", "None", "Old"), names(list(alice, "&sort=name")));
        assertEquals(List.of("Old", "None", "Fresh", "Edge"), names(list(alice, "&sort=name,desc")));
        JsonNode first = list(alice, "&size=3&page=0&held=false&stale=false");
        assertEquals(List.of("Edge"), names(first));
        JsonNode p0 = list(alice, "&size=3");
        assertEquals(3, p0.get("items").size());
        assertEquals(4, p0.get("totalElements").asInt());
        assertEquals(2, p0.get("totalPages").asInt());
        assertEquals(List.of("Old"), names(list(alice, "&size=3&page=1")));
        alice.getJson("/api/v1/instruments?sort=price", 400);
    }

    @Test
    void overriddenReportsTheUsersOwnEditsOnlyAndResetClearsIt() throws Exception {
        catalog();
        UUID fresh = instrumentIds.get(0);
        JsonNode current = alice.getJson("/api/v1/instruments/" + fresh, 200);
        Map<String, Object> body = new HashMap<>();
        for (String f : List.of("type", "name", "symbol", "exchange", "isin", "amfiCode", "yahooSymbol", "currency")) {
            JsonNode v = current.get(f);
            body.put(f, v == null || v.isNull() ? null : v.asText());
        }
        body.put("name", "Fresh Renamed " + tag);
        alice.putJson("/api/v1/instruments/" + fresh, body, 200);

        JsonNode mine = list(alice, "");
        assertTrue(item(mine, "Fresh Renamed").get("overridden").asBoolean());
        assertEquals(List.of("name"), strings(item(mine, "Fresh Renamed").get("overriddenFields")));
        assertFalse(item(mine, "Edge").get("overridden").asBoolean());
        assertFalse(item(list(bob, ""), "Fresh").get("overridden").asBoolean(), "Bob sees the catalog row");

        alice.deleteJson("/api/v1/instruments/" + fresh + "/overrides", 200);
        assertFalse(item(list(alice, ""), "Fresh").get("overridden").asBoolean());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ------------------------------------------------------------------ price history

    @Test
    void priceHistoryPagesNewestFirstWithSourceAndEditableAndTheUnpagedShapeIsUnchanged() throws Exception {
        UUID s = stock("History", "05");
        for (int i = 0; i < 35; i++) {
            feedPrice(s, today.minusDays(i), String.valueOf(100 + i));
        }
        // Alice's own MANUAL price on a date the feed also has stands in for it.
        alice.postJson("/api/v1/instruments/" + s + "/price",
                Map.of("price", "555", "asOf", today.minusDays(3).toString()), 200);

        JsonNode p0 = alice.getJson("/api/v1/instruments/" + s + "/prices/page", 200);
        assertEquals(30, p0.get("size").asInt(), "default size 30");
        assertEquals(30, p0.get("items").size());
        assertEquals(35, p0.get("totalElements").asInt(), "one row per date");
        assertEquals(2, p0.get("totalPages").asInt());
        assertEquals(today.toString(), p0.get("items").get(0).get("asOf").asText());
        assertEquals("YAHOO", p0.get("items").get(0).get("source").asText());
        assertFalse(p0.get("items").get(0).get("editable").asBoolean());
        JsonNode manual = p0.get("items").get(3);
        assertEquals(today.minusDays(3).toString(), manual.get("asOf").asText());
        assertEquals("MANUAL", manual.get("source").asText());
        assertTrue(manual.get("editable").asBoolean());
        assertEquals(0, new BigDecimal("555").compareTo(manual.get("close").decimalValue()));

        JsonNode p1 = alice.getJson("/api/v1/instruments/" + s + "/prices/page?page=1", 200);
        assertEquals(5, p1.get("items").size());
        assertEquals(today.minusDays(34).toString(), p1.get("items").get(4).get("asOf").asText());
        JsonNode small = alice.getJson("/api/v1/instruments/" + s + "/prices/page?size=10&page=3", 200);
        assertEquals(5, small.get("items").size());
        assertEquals(4, small.get("totalPages").asInt());

        JsonNode legacy = alice.getJson("/api/v1/instruments/" + s + "/prices", 200);
        assertTrue(legacy.isArray());
        assertEquals(35, legacy.size());
        assertEquals(today.toString(), legacy.get(0).get("asOf").asText());
        assertTrue(legacy.get(0).has("source") && legacy.get(0).has("editable") && legacy.get(0).has("close"));

        JsonNode bobs = bob.getJson("/api/v1/instruments/" + s + "/prices/page?size=5&page=0", 200);
        assertEquals(35, bobs.get("totalElements").asInt());
        assertEquals("YAHOO", bobs.get("items").get(3).get("source").asText(), "Alice's manual price is hers alone");
        assertFalse(bobs.get("items").get(3).get("editable").asBoolean());
        alice.getJson("/api/v1/instruments/" + UUID.randomUUID() + "/prices/page", 404);
    }
}
