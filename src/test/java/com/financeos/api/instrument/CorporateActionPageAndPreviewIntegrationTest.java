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
 * GET /corporate-actions/page (filters, sorts, paging, quantity before / after, tenancy, the unpaged list
 * untouched) and POST /instruments/{id}/corporate-actions/preview (split, bonus, demerger, merger across
 * brokers, nothing persisted), against real persistence (H2).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorporateActionPageAndPreviewIntegrationTest {

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
        String a = "capp-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        String b = "capp-b-" + UUID.randomUUID() + "@example.test";
        bob = ApiTestClient.signUp(mockMvc, mapper, b);
        bobId = userRepository.findByEmail(b).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId, bobId));
        for (UUID id : instrumentIds) {
            jdbc.update("DELETE FROM corporate_actions WHERE target_instrument_id = ?", id.toString());
        }
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    // ------------------------------------------------------------------ helpers

    private UUID stock(String name, String symbol, String isinSuffix) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "stock");
        body.put("name", name + " " + tag);
        body.put("symbol", symbol + tag.substring(0, 4));
        body.put("exchange", "NSE");
        body.put("isin", "INE" + tag + isinSuffix);
        UUID id = alice.create("/api/v1/instruments", body);
        instrumentIds.add(id);
        return id;
    }

    private static UUID broker(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "broker", "name", name, "provider", "Zerodha",
                "clientId", "CAPP1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static void trade(ApiTestClient client, UUID broker, UUID instrument, String type, String qty, String price,
                              LocalDate date) throws Exception {
        client.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", qty, "price", price,
                "tradeDate", date.toString()));
    }

    private static Map<String, Object> split(int from, int to, LocalDate ex) {
        return Map.of("type", "split", "ratioFrom", from, "ratioTo", to, "exDate", ex.toString());
    }

    private static Map<String, Object> bonus(int from, int to, LocalDate ex) {
        return Map.of("type", "bonus", "ratioFrom", from, "ratioTo", to, "exDate", ex.toString());
    }

    private static Map<String, Object> demerger(UUID child, int from, int to, LocalDate ex, int costPct) {
        return Map.of("type", "demerger", "ratioFrom", from, "ratioTo", to, "exDate", ex.toString(),
                "targetInstrumentId", child.toString(), "costAllocationPct", costPct);
    }

    private static Map<String, Object> merger(UUID acquirer, int from, int to, LocalDate ex) {
        return Map.of("type", "merger", "ratioFrom", from, "ratioTo", to, "exDate", ex.toString(),
                "targetInstrumentId", acquirer.toString());
    }

    private JsonNode preview(UUID instrument, Map<String, Object> body, int status) throws Exception {
        return alice.postJson("/api/v1/instruments/" + instrument + "/corporate-actions/preview", body, status);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                aliceId.toString());
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual != null && actual.isNumber(), "expected " + expected + " but was " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(n -> out.add(n.get("instrumentName").asText()));
        return out;
    }

    // ------------------------------------------------------------------ page

    /** A, B, C with a split / bonus / split at ex-dates 30, 20, 10 days ago; Alice holds only A. */
    private UUID[] threeActions() throws Exception {
        UUID a = stock("Alpha", "ALP", "01");
        UUID b = stock("Bravo", "BRV", "02");
        UUID c = stock("Charlie", "CHA", "03");
        trade(alice, broker(alice, "A1"), a, "buy", "10", "100", today.minusDays(100));
        alice.create("/api/v1/instruments/" + a + "/corporate-actions", split(1, 2, today.minusDays(30)));
        alice.create("/api/v1/instruments/" + b + "/corporate-actions", bonus(1, 1, today.minusDays(20)));
        alice.create("/api/v1/instruments/" + c + "/corporate-actions", split(1, 5, today.minusDays(10)));
        return new UUID[]{a, b, c};
    }

    @Test
    void pageSortsNewestOldestAndByName() throws Exception {
        threeActions();
        String q = "?search=" + tag;
        assertEquals(List.of("Charlie " + tag, "Bravo " + tag, "Alpha " + tag),
                names(alice.getJson("/api/v1/corporate-actions/page" + q, 200)), "newest by ex-date is the default");
        assertEquals(List.of("Alpha " + tag, "Bravo " + tag, "Charlie " + tag),
                names(alice.getJson("/api/v1/corporate-actions/page" + q + "&sort=oldest", 200)));
        assertEquals(List.of("Alpha " + tag, "Bravo " + tag, "Charlie " + tag),
                names(alice.getJson("/api/v1/corporate-actions/page" + q + "&sort=name", 200)));
        alice.getJson("/api/v1/corporate-actions/page?sort=bogus", 400);
    }

    @Test
    void pageFiltersByTypeAndSearchCaseInsensitivelyOnNameAndSymbol() throws Exception {
        threeActions();
        JsonNode bonuses = alice.getJson("/api/v1/corporate-actions/page?type=bonus", 200);
        assertEquals(List.of("Bravo " + tag), names(bonuses));
        assertEquals(1, bonuses.get("totalElements").asInt());
        assertEquals(2, alice.getJson("/api/v1/corporate-actions/page?type=split", 200).get("totalElements").asInt());
        assertEquals(0, alice.getJson("/api/v1/corporate-actions/page?type=merger", 200).get("totalElements").asInt());

        assertEquals(List.of("Bravo " + tag), names(alice.getJson(
                "/api/v1/corporate-actions/page?search=" + ("bravo " + tag).toLowerCase(Locale.ROOT), 200)));
        assertEquals(List.of("Charlie " + tag), names(alice.getJson(
                "/api/v1/corporate-actions/page?search=cha" + tag.substring(0, 4).toLowerCase(Locale.ROOT), 200)),
                "the symbol matches too");
        assertEquals(0, alice.getJson("/api/v1/corporate-actions/page?search=zzz" + tag, 200).get("totalElements").asInt());
        assertEquals(List.of("Charlie " + tag), names(alice.getJson(
                "/api/v1/corporate-actions/page?search=" + tag + "&type=split&size=1", 200)));
    }

    @Test
    void pagePagesWithTotalsAndIsTheCallersOwn() throws Exception {
        threeActions();
        String q = "?search=" + tag + "&size=2";
        JsonNode first = alice.getJson("/api/v1/corporate-actions/page" + q, 200);
        assertEquals(2, first.get("items").size());
        assertEquals(3, first.get("totalElements").asInt());
        assertEquals(2, first.get("totalPages").asInt());
        assertEquals(0, first.get("page").asInt());
        JsonNode second = alice.getJson("/api/v1/corporate-actions/page" + q + "&page=1", 200);
        assertEquals(List.of("Alpha " + tag), names(second));
        assertEquals(25, alice.getJson("/api/v1/corporate-actions/page", 200).get("size").asInt(), "default size 25");

        JsonNode bobs = bob.getJson("/api/v1/corporate-actions/page?search=" + tag, 200);
        assertEquals(0, bobs.get("totalElements").asInt());
        assertEquals(0, bobs.get("items").size());
    }

    @Test
    void pageRowsCarryTheActionAndTheQuantityBeforeAndAfter() throws Exception {
        UUID[] ids = threeActions();
        UUID child = stock("Delta", "DEL", "04");
        UUID parentCa = alice.create("/api/v1/instruments/" + ids[0] + "/corporate-actions",
                demerger(child, 1, 1, today.minusDays(5), 30));
        alice.putJson("/api/v1/instruments/" + ids[0] + "/corporate-actions/" + parentCa,
                Map.of("type", "demerger", "ratioFrom", 1, "ratioTo", 1, "exDate", today.minusDays(5).toString(),
                        "targetInstrumentId", child.toString(), "costAllocationPct", 30,
                        "fractionalCashInLieu", 12.5, "notes", "spin-off"), 200);

        JsonNode page = alice.getJson("/api/v1/corporate-actions/page?search=Alpha " + tag + "&sort=oldest", 200);
        assertEquals(2, page.get("totalElements").asInt());
        JsonNode split = page.get("items").get(0);
        assertEquals("split", split.get("type").asText());
        assertEquals(1, split.get("ratioFrom").asInt());
        assertEquals(2, split.get("ratioTo").asInt());
        assertEquals(today.minusDays(30).toString(), split.get("exDate").asText());
        assertDecimal("10", split.get("quantityBefore"));
        assertDecimal("20", split.get("quantityAfter"));

        JsonNode dem = page.get("items").get(1);
        assertEquals("demerger", dem.get("type").asText());
        assertEquals(child.toString(), dem.get("targetInstrumentId").asText());
        assertEquals("Delta " + tag, dem.get("targetInstrumentName").asText());
        assertDecimal("30", dem.get("costAllocationPct"));
        assertDecimal("12.5", dem.get("fractionalCashInLieu"));
        assertEquals("spin-off", dem.get("notes").asText());
        assertDecimal("20", dem.get("quantityBefore"));
        assertDecimal("20", dem.get("quantityAfter"));

        JsonNode noHolding = alice.getJson("/api/v1/corporate-actions/page?search=Bravo " + tag, 200).get("items").get(0);
        assertTrue(noHolding.get("quantityBefore").isNull() && noHolding.get("quantityAfter").isNull(),
                "no holding, no effect");
    }

    @Test
    void thePageSumsQuantityAcrossBrokersAndTheUnpagedListIsUnchanged() throws Exception {
        UUID s = stock("Sigma", "SIG", "05");
        trade(alice, broker(alice, "B1"), s, "buy", "10", "100", today.minusDays(100));
        trade(alice, broker(alice, "B2"), s, "buy", "5", "100", today.minusDays(90));
        alice.create("/api/v1/instruments/" + s + "/corporate-actions", bonus(1, 2, today.minusDays(40)));

        JsonNode row = alice.getJson("/api/v1/corporate-actions/page?search=" + tag, 200).get("items").get(0);
        assertDecimal("15", row.get("quantityBefore"));
        assertDecimal("30", row.get("quantityAfter"));

        JsonNode legacy = alice.getJson("/api/v1/corporate-actions", 200);
        assertTrue(legacy.isArray());
        assertEquals(1, legacy.size());
        assertFalse(legacy.get(0).has("quantityBefore"), "the unpaged list keeps its shape");
        assertEquals("bonus", legacy.get(0).get("type").asText());
    }

    // ------------------------------------------------------------------ preview

    @Test
    void previewOfASplitShowsEachBrokerBeforeAndAfterAndPersistsNothing() throws Exception {
        UUID s = stock("Split Co", "SPC", "06");
        UUID b1 = broker(alice, "P1");
        UUID b2 = broker(alice, "P2");
        trade(alice, b1, s, "buy", "10", "100", today.minusDays(100));
        trade(alice, b2, s, "buy", "4", "200", today.minusDays(90));
        int actions = count("corporate_actions");
        int holdings = count("holdings");

        JsonNode answer = preview(s, split(1, 2, today.minusDays(50)), 200);
        JsonNode rows = answer.get("holdings");
        assertEquals(2, rows.size());
        for (JsonNode r : rows) {
            if (r.get("brokerAccountId").asText().equals(b1.toString())) {
                assertEquals("P1", r.get("brokerAccountName").asText());
                assertDecimal("10", r.get("quantityBefore"));
                assertDecimal("20", r.get("quantityAfter"));
                assertDecimal("100", r.get("avgCostBefore"));
                assertDecimal("50", r.get("avgCostAfter"));
                assertDecimal("1000", r.get("openCostBefore"));
                assertDecimal("1000", r.get("openCostAfter"));
            } else {
                assertEquals(b2.toString(), r.get("brokerAccountId").asText());
                assertDecimal("4", r.get("quantityBefore"));
                assertDecimal("8", r.get("quantityAfter"));
                assertDecimal("200", r.get("avgCostBefore"));
                assertDecimal("100", r.get("avgCostAfter"));
            }
            assertTrue(r.get("childInstrumentId").isNull());
            assertTrue(r.get("childQuantity").isNull());
        }
        assertEquals(actions, count("corporate_actions"), "nothing saved");
        assertEquals(holdings, count("holdings"));
        assertEquals(0, alice.getJson("/api/v1/corporate-actions", 200).size());
        assertEquals(0, alice.getJson("/api/v1/instruments/" + s + "/corporate-actions", 200).size());
        for (JsonNode p : alice.getJson("/api/v1/investments/positions", 200).get("positions")) {
            assertTrue(p.get("quantity").decimalValue().compareTo(new BigDecimal("10")) == 0
                    || p.get("quantity").decimalValue().compareTo(new BigDecimal("4")) == 0, "positions unchanged");
        }
    }

    @Test
    void previewOfABonusAddsFreeSharesAndLowersAverageCost() throws Exception {
        UUID s = stock("Bonus Co", "BON", "07");
        trade(alice, broker(alice, "Q1"), s, "buy", "10", "100", today.minusDays(100));
        JsonNode r = preview(s, bonus(1, 2, today.minusDays(50)), 200).get("holdings").get(0);
        assertDecimal("10", r.get("quantityBefore"));
        assertDecimal("20", r.get("quantityAfter"));
        assertDecimal("100", r.get("avgCostBefore"));
        assertDecimal("50", r.get("avgCostAfter"));
        assertEquals(0, count("corporate_actions"));
    }

    @Test
    void previewOfADemergerShowsParentCostCarvedAndTheChildSharesWithoutCreatingTheChildHolding() throws Exception {
        UUID parent = stock("Parent", "PAR", "08");
        UUID child = stock("Child", "CHD", "09");
        UUID b1 = broker(alice, "D1");
        UUID b2 = broker(alice, "D2");
        trade(alice, b1, parent, "buy", "10", "100", today.minusDays(100));
        trade(alice, b2, parent, "buy", "20", "100", today.minusDays(100));
        int holdings = count("holdings");

        JsonNode rows = preview(parent, demerger(child, 1, 1, today.minusDays(50), 40), 200).get("holdings");
        assertEquals(2, rows.size());
        for (JsonNode r : rows) {
            boolean small = r.get("brokerAccountId").asText().equals(b1.toString());
            assertDecimal(small ? "10" : "20", r.get("quantityBefore"));
            assertDecimal(small ? "10" : "20", r.get("quantityAfter"));
            assertDecimal("100", r.get("avgCostBefore"));
            assertDecimal("60", r.get("avgCostAfter"));
            assertEquals(child.toString(), r.get("childInstrumentId").asText());
            assertDecimal(small ? "10" : "20", r.get("childQuantity"));
            assertDecimal(small ? "400" : "800", r.get("childCost"));
        }
        assertEquals(holdings, count("holdings"), "the child holdings it needs were rolled back");
        assertEquals(0, count("corporate_actions"));
        assertEquals(0, alice.getJson("/api/v1/corporate-actions", 200).size());
        assertDecimal("100", alice.getJson("/api/v1/investments/positions", 200).get("positions").get(0).get("avgCost"));
    }

    @Test
    void previewOfADemergerWithFractionalSharesShowsTheCashInLieuResult() throws Exception {
        UUID parent = stock("Frac Parent", "FRP", "10");
        UUID child = stock("Frac Child", "FRC", "11");
        trade(alice, broker(alice, "F1"), parent, "buy", "10", "100", today.minusDays(100));
        Map<String, Object> body = new HashMap<>(demerger(child, 2, 3, today.minusDays(50), 50));
        body.put("fractionalCashInLieu", 30);
        JsonNode r = preview(parent, body, 200).get("holdings").get(0);
        // 10 parent at 2:3 -> 15 child units, all whole: no fraction. 7 parent below -> 10.5, a fraction.
        assertDecimal("15", r.get("childQuantity"));
        assertDecimal("0", r.get("fractionalRealized"));

        UUID parent2 = stock("Frac Parent2", "FR2", "12");
        trade(alice, broker(alice, "F2"), parent2, "buy", "7", "100", today.minusDays(100));
        JsonNode r2 = preview(parent2, body, 200).get("holdings").get(0);
        // 7 * 3/2 = 10.5 entitled: 10 whole units, 0.5 settled for the 30 cash in lieu.
        assertDecimal("10", r2.get("childQuantity"));
        // Fraction 0.5 at cost 0.5 * (7 * 100 * 50% / 10.5) = 16.67 against 30 received: +13.33.
        assertEquals(0, new BigDecimal("13.33").compareTo(
                r2.get("fractionalRealized").decimalValue().setScale(2, java.math.RoundingMode.HALF_UP)));
    }

    @Test
    void previewOfAMergerClosesTheParentAndMovesItsCostToTheAcquirer() throws Exception {
        UUID target = stock("Old Co", "OLD", "13");
        UUID acquirer = stock("New Co", "NEW", "14");
        UUID b1 = broker(alice, "M1");
        trade(alice, b1, target, "buy", "10", "100", today.minusDays(100));
        int holdings = count("holdings");

        JsonNode r = preview(target, merger(acquirer, 1, 2, today.minusDays(50)), 200).get("holdings").get(0);
        assertDecimal("10", r.get("quantityBefore"));
        assertDecimal("0", r.get("quantityAfter"));
        assertEquals(acquirer.toString(), r.get("childInstrumentId").asText());
        assertDecimal("20", r.get("childQuantity"));
        assertDecimal("1000", r.get("childCost"));
        assertEquals(holdings, count("holdings"));
        assertEquals(0, count("corporate_actions"));
    }

    @Test
    void previewWithoutHoldingsIsEmptyAndABadRequestIsRejectedLikeACreate() throws Exception {
        UUID s = stock("Nobody", "NOB", "15");
        assertEquals(0, preview(s, split(1, 2, today.minusDays(5)), 200).get("holdings").size());
        UUID child = stock("Kid", "KID", "16");
        preview(s, Map.of("type", "demerger", "ratioFrom", 1, "ratioTo", 1, "exDate", today.toString()), 400);
        preview(s, Map.of("type", "demerger", "ratioFrom", 1, "ratioTo", 1, "exDate", today.toString(),
                "targetInstrumentId", child.toString()), 400);
        preview(UUID.randomUUID(), split(1, 2, today), 404);
        preview(s, Map.of("type", "split", "ratioFrom", 0, "ratioTo", 2, "exDate", today.toString()), 400);
        assertEquals(0, count("corporate_actions"));
    }

    @Test
    void previewOnlyCoversTheCallersOwnHoldings() throws Exception {
        UUID s = stock("Shared", "SHR", "17");
        trade(alice, broker(alice, "S1"), s, "buy", "10", "100", today.minusDays(100));
        JsonNode bobs = bob.postJson("/api/v1/instruments/" + s + "/corporate-actions/preview",
                split(1, 2, today.minusDays(5)), 200);
        assertEquals(0, bobs.get("holdings").size());
    }
}
