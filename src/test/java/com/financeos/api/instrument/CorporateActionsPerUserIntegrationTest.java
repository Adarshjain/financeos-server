package com.financeos.api.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corporate actions are per user, against real persistence (H2) with two users holding the same
 * instruments: each user's actions apply to their own lots only, the list and every write are the
 * caller's own, a demerger gives only the caller a child holding, and an identifier edit (repoint)
 * carries the editor's own actions to the target so their positions and cost do not move.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorporateActionsPerUserIntegrationTest {

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
        String a = "capu-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        String b = "capu-b-" + UUID.randomUUID() + "@example.test";
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

    private String isin(String suffix) {
        return "INE" + tag + suffix;
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
                "clientId", "CAPU1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static void trade(ApiTestClient client, UUID broker, UUID instrument, String type, String qty, String price,
                              LocalDate date) throws Exception {
        client.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", qty, "price", price,
                "tradeDate", date.toString()));
    }

    /** The user's own MANUAL price (it follows them on a repoint, so values stay comparable). */
    private void manualPrice(ApiTestClient client, UUID instrument, String price) throws Exception {
        client.postJson("/api/v1/instruments/" + instrument + "/price",
                Map.of("price", price, "asOf", today.toString()), 200);
    }

    private static UUID action(ApiTestClient client, UUID instrument, Map<String, Object> body) throws Exception {
        return client.create("/api/v1/instruments/" + instrument + "/corporate-actions", body);
    }

    private static Map<String, Object> split(int from, int to, LocalDate ex) {
        return Map.of("type", "split", "ratioFrom", from, "ratioTo", to, "exDate", ex.toString());
    }

    private static Map<String, Object> demerger(UUID child, int from, int to, LocalDate ex, int costPct) {
        return Map.of("type", "demerger", "ratioFrom", from, "ratioTo", to, "exDate", ex.toString(),
                "targetInstrumentId", child.toString(), "costAllocationPct", costPct);
    }

    private static Map<String, Object> merger(UUID acquirer, int from, int to, LocalDate ex) {
        return Map.of("type", "merger", "ratioFrom", from, "ratioTo", to, "exDate", ex.toString(),
                "targetInstrumentId", acquirer.toString());
    }

    private static List<JsonNode> positions(ApiTestClient client, UUID instrument) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode p : client.getJson("/api/v1/investments/positions", 200).get("positions")) {
            if (p.get("instrument").get("id").asText().equals(instrument.toString())) {
                out.add(p);
            }
        }
        return out;
    }

    private static JsonNode position(ApiTestClient client, UUID instrument) throws Exception {
        List<JsonNode> found = positions(client, instrument);
        assertEquals(1, found.size(), "one position of " + instrument);
        return found.get(0);
    }

    /** A position without what names its instrument (which a repoint changes by design). */
    private static JsonNode figures(JsonNode position) {
        ObjectNode copy = position.deepCopy();
        copy.remove("instrument");
        return copy;
    }

    /** {@code client}'s identifier edit of {@code id} to a new ISIN; the instrument they now hold. */
    private UUID repoint(ApiTestClient client, UUID id, String newIsin, int status) throws Exception {
        JsonNode current = client.getJson("/api/v1/instruments/" + id, 200);
        Map<String, Object> body = new HashMap<>();
        for (String f : List.of("type", "name", "symbol", "exchange", "isin", "amfiCode", "yahooSymbol", "currency")) {
            JsonNode v = current.get(f);
            body.put(f, v == null || v.isNull() ? null : v.asText());
        }
        body.put("isin", newIsin);
        JsonNode answer = client.putJson("/api/v1/instruments/" + id, body, status);
        if (status != 200) {
            return null;
        }
        UUID target = UUID.fromString(answer.get("id").asText());
        instrumentIds.add(target);
        return target;
    }

    private String ownerOf(UUID action) {
        return jdbc.queryForObject("SELECT user_id FROM corporate_actions WHERE id = ?", String.class, action.toString());
    }

    private String column(UUID action, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM corporate_actions WHERE id = ?", String.class,
                action.toString());
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertDecimal(expected, actual, "");
    }

    private static void assertDecimal(String expected, JsonNode actual, String why) {
        assertTrue(actual != null && actual.isNumber(), why + ": expected " + expected + " but was " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), why + ": " + expected + " vs " + actual);
    }

    // ------------------------------------------------------------------ tenancy

    @Test
    void eachUsersCorporateActionsApplyToTheirOwnLotsAndOnlyTheyCanSeeOrChangeThem() throws Exception {
        UUID s = stock("Split Co", "SPC", isin("01"));
        trade(alice, broker(alice, "A"), s, "buy", "10", "100", today.minusDays(100));
        trade(bob, broker(bob, "B"), s, "buy", "10", "100", today.minusDays(100));

        UUID hers = action(alice, s, split(1, 2, today.minusDays(50)));
        assertEquals(aliceId.toString(), ownerOf(hers));
        assertDecimal("20", position(alice, s).get("quantity"));
        assertDecimal("10", position(bob, s).get("quantity"));
        assertDecimal("50", position(alice, s).get("avgCost"));
        assertDecimal("100", position(bob, s).get("avgCost"));

        assertEquals(1, alice.getJson("/api/v1/corporate-actions", 200).size());
        assertEquals(hers.toString(), alice.getJson("/api/v1/corporate-actions", 200).get(0).get("id").asText());
        assertEquals(0, bob.getJson("/api/v1/corporate-actions", 200).size(), "the list is the caller's own");
        assertEquals(0, bob.getJson("/api/v1/instruments/" + s + "/corporate-actions", 200).size());

        bob.putJson("/api/v1/instruments/" + s + "/corporate-actions/" + hers, split(1, 5, today.minusDays(50)), 404);
        bob.deleteJson("/api/v1/instruments/" + s + "/corporate-actions/" + hers, 404);
        assertEquals("2", column(hers, "ratio_to"), "Bob's attempts changed nothing");
        assertDecimal("20", position(alice, s).get("quantity"));

        UUID his = action(bob, s, Map.of("type", "bonus", "ratioFrom", 1, "ratioTo", 2,
                "exDate", today.minusDays(40).toString()));
        assertEquals(bobId.toString(), ownerOf(his));
        assertDecimal("20", position(bob, s).get("quantity"));
        assertDecimal("20", position(alice, s).get("quantity"), "Bob's bonus is not hers");
        assertEquals(List.of(hers.toString()), ids(alice.getJson("/api/v1/instruments/" + s + "/corporate-actions", 200)));
        assertEquals(List.of(his.toString()), ids(bob.getJson("/api/v1/corporate-actions", 200)));

        // Her own write paths still work, through her id only.
        alice.putJson("/api/v1/instruments/" + s + "/corporate-actions/" + hers, split(1, 3, today.minusDays(50)), 200);
        assertDecimal("30", position(alice, s).get("quantity"));
        alice.putJson("/api/v1/instruments/" + s + "/corporate-actions/" + his, split(1, 3, today.minusDays(50)), 404);
        alice.deleteJson("/api/v1/instruments/" + s + "/corporate-actions/" + hers, 204);
        assertDecimal("10", position(alice, s).get("quantity"));
        assertDecimal("20", position(bob, s).get("quantity"));
    }

    private static List<String> ids(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    @Test
    void aDemergerSeedsAndCreatesAChildHoldingForTheCallerOnly() throws Exception {
        UUID parent = stock("Parent Co", "PRC", isin("02"));
        UUID child = stock("Child Co", "CHC", isin("03"));
        trade(alice, broker(alice, "A"), parent, "buy", "10", "100", today.minusDays(100));
        trade(bob, broker(bob, "B"), parent, "buy", "10", "100", today.minusDays(100));

        action(alice, parent, demerger(child, 1, 1, today.minusDays(50), 30));

        assertDecimal("10", position(alice, child).get("quantity"));
        assertDecimal("300", position(alice, child).get("invested"));
        assertDecimal("700", position(alice, parent).get("invested"));
        assertEquals(0, positions(bob, child).size(), "Bob has no child holding and no seeded shares");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM holdings WHERE user_id = ? AND instrument_id = ?",
                Integer.class, bobId.toString(), child.toString()));
        assertDecimal("1000", position(bob, parent).get("invested"), "Bob's cost is not carved");
    }

    // ------------------------------------------------------------------ repoint carries the user's own

    @Test
    void aRepointCarriesASplitOnTheSourceAndLeavesAnotherUsersActionsWhereTheyAre() throws Exception {
        UUID s = stock("Carry Split", "CSP", isin("11"));
        UUID aliceBroker = broker(alice, "A");
        trade(alice, aliceBroker, s, "buy", "10", "100", today.minusDays(300));
        trade(alice, aliceBroker, s, "sell", "4", "80", today.minusDays(20));
        UUID hers = action(alice, s, split(1, 2, today.minusDays(50)));
        manualPrice(alice, s, "70");
        trade(bob, broker(bob, "B"), s, "buy", "6", "100", today.minusDays(300));
        UUID his = action(bob, s, split(1, 3, today.minusDays(60)));
        manualPrice(bob, s, "40");
        JsonNode aliceBefore = figures(position(alice, s));
        JsonNode aliceSummaryBefore = alice.getJson("/api/v1/investments/summary", 200);
        JsonNode bobBefore = position(bob, s);

        UUID n = repoint(alice, s, isin("12"), 200);

        assertNotEquals(s, n);
        assertEquals(aliceBefore, figures(position(alice, n)), "same quantity, cost, realised, XIRR");
        assertEquals(aliceSummaryBefore, alice.getJson("/api/v1/investments/summary", 200));
        assertDecimal("16", position(alice, n).get("quantity"));
        assertEquals(n.toString(), column(hers, "instrument_id"), "her split moved with her");
        assertEquals(List.of(hers.toString()), ids(alice.getJson("/api/v1/instruments/" + n + "/corporate-actions", 200)));
        assertEquals(s.toString(), column(his, "instrument_id"), "Bob's split stays on the source");
        assertEquals(bobBefore, position(bob, s));
        assertEquals(0, positions(alice, s).size());
    }

    @Test
    void aRepointOfADemergerParentOrChildKeepsBothSidesPositions() throws Exception {
        UUID parent = stock("Carry Parent", "CPA", isin("21"));
        UUID child = stock("Carry Child", "CCH", isin("22"));
        UUID aliceBroker = broker(alice, "A");
        trade(alice, aliceBroker, parent, "buy", "10", "100", today.minusDays(300));
        trade(alice, aliceBroker, parent, "buy", "5", "120", today.minusDays(200));
        UUID d = action(alice, parent, demerger(child, 3, 1, today.minusDays(100), 25));
        trade(alice, aliceBroker, child, "sell", "2", "60", today.minusDays(10));
        manualPrice(alice, parent, "90");
        manualPrice(alice, child, "40");
        JsonNode parentBefore = figures(position(alice, parent));
        JsonNode childBefore = figures(position(alice, child));
        JsonNode summaryBefore = alice.getJson("/api/v1/investments/summary", 200);

        UUID newParent = repoint(alice, parent, isin("23"), 200);
        assertEquals(newParent.toString(), column(d, "instrument_id"));
        assertEquals(child.toString(), column(d, "target_instrument_id"));
        assertEquals(parentBefore, figures(position(alice, newParent)));
        assertEquals(childBefore, figures(position(alice, child)), "the child still finds its parent holding");
        assertEquals(summaryBefore, alice.getJson("/api/v1/investments/summary", 200));

        UUID newChild = repoint(alice, child, isin("24"), 200);
        assertEquals(newChild.toString(), column(d, "target_instrument_id"));
        assertEquals(parentBefore, figures(position(alice, newParent)));
        assertEquals(childBefore, figures(position(alice, newChild)), "the seeded lots follow the child");
        assertEquals(summaryBefore, alice.getJson("/api/v1/investments/summary", 200));
    }

    @Test
    void aRepointOfAMergerAcquirerKeepsTheSeededPosition() throws Exception {
        UUID transferor = stock("Carry Transferor", "CTR", isin("31"));
        UUID acquirer = stock("Carry Acquirer", "CAQ", isin("32"));
        UUID aliceBroker = broker(alice, "A");
        trade(alice, aliceBroker, transferor, "buy", "7", "100", today.minusDays(400));
        UUID m = action(alice, transferor, merger(acquirer, 2, 3, today.minusDays(100)));
        trade(alice, aliceBroker, acquirer, "buy", "4", "90", today.minusDays(30));
        manualPrice(alice, acquirer, "95");
        JsonNode acquirerBefore = figures(position(alice, acquirer));
        JsonNode summaryBefore = alice.getJson("/api/v1/investments/summary", 200);
        assertDecimal("14", acquirerBefore.get("quantity"));   // floor(7 × 3 ÷ 2) + 4

        UUID newAcquirer = repoint(alice, acquirer, isin("33"), 200);

        assertEquals(newAcquirer.toString(), column(m, "target_instrument_id"));
        assertEquals(acquirerBefore, figures(position(alice, newAcquirer)));
        assertEquals(0, positions(alice, transferor).size(), "the transferor stays closed by the merger");
        assertEquals(summaryBefore, alice.getJson("/api/v1/investments/summary", 200));
    }

    @Test
    void aRepointThatWouldTurnADemergerIntoItselfIsRefusedAndMovesNothing() throws Exception {
        UUID parent = stock("Self Parent", "SPA", isin("41"));
        UUID child = stock("Self Child", "SCH", isin("42"));
        UUID aliceBroker = broker(alice, "A");
        trade(alice, aliceBroker, parent, "buy", "10", "100", today.minusDays(300));
        UUID d = action(alice, parent, demerger(child, 1, 1, today.minusDays(100), 40));
        JsonNode childBefore = position(alice, child);

        JsonNode refused = alice.putJson("/api/v1/instruments/" + parent, repointBody(alice, parent, isin("42")), 400);
        assertTrue(refused.get("message").asText().contains("into itself"), refused.toString());
        JsonNode refusedChild = alice.putJson("/api/v1/instruments/" + child, repointBody(alice, child, isin("41")), 400);
        assertTrue(refusedChild.get("message").asText().contains("demerger of Self Parent " + tag), refusedChild.toString());
        assertEquals(parent.toString(), column(d, "instrument_id"));
        assertEquals(child.toString(), column(d, "target_instrument_id"));
        assertEquals(childBefore, position(alice, child));

        // Bob's own move onto the same pair is not blocked by Alice's demerger.
        trade(bob, broker(bob, "B"), parent, "buy", "1", "100", today.minusDays(5));
        assertEquals(child, repoint(bob, parent, isin("42"), 200));
        instrumentIds.remove(instrumentIds.size() - 1);
        assertEquals(1, positions(bob, child).size());
    }

    private Map<String, Object> repointBody(ApiTestClient client, UUID id, String newIsin) throws Exception {
        JsonNode current = client.getJson("/api/v1/instruments/" + id, 200);
        Map<String, Object> body = new HashMap<>();
        for (String f : List.of("type", "name", "symbol", "exchange", "isin", "amfiCode", "yahooSymbol", "currency")) {
            JsonNode v = current.get(f);
            body.put(f, v == null || v.isNull() ? null : v.asText());
        }
        body.put("isin", newIsin);
        return body;
    }

    // ------------------------------------------------------------------ ticker after a move

    @Test
    void aRepointKeepsTheTickerTheUserSawWhenTheNewRowCannotHaveIt() throws Exception {
        UUID s = stock("Ticker Keep", "TKK", isin("51"));
        String catalogSymbol = "TKK" + tag.substring(0, 4);
        trade(alice, broker(alice, "A"), s, "buy", "1", "10", today.minusDays(5));

        UUID n = repoint(alice, s, isin("52"), 200);

        assertNull(jdbc.queryForObject("SELECT symbol FROM instruments WHERE id = ?", String.class, n.toString()),
                "the ticker still belongs to the source row");
        JsonNode seen = alice.getJson("/api/v1/instruments/" + n, 200);
        assertEquals(catalogSymbol, seen.get("symbol").asText());
        assertEquals("NSE", seen.get("exchange").asText());
        assertEquals(catalogSymbol, position(alice, n).get("instrument").get("symbol").asText());
        assertNull(bob.getJson("/api/v1/instruments/" + n, 200).get("symbol").textValue(), "an override, not the catalog's");

        // Her own symbol override on the source is the ticker she keeps.
        UUID t = stock("Ticker Mine", "TKM", isin("53"));
        trade(alice, broker(alice, "B"), t, "buy", "1", "10", today.minusDays(5));
        Map<String, Object> rename = repointBody(alice, t, isin("53"));
        rename.put("symbol", "MYTK" + tag.substring(0, 3));
        alice.putJson("/api/v1/instruments/" + t, rename, 200);
        UUID moved = repoint(alice, t, isin("54"), 200);
        assertEquals("MYTK" + tag.substring(0, 3), alice.getJson("/api/v1/instruments/" + moved, 200).get("symbol").asText());
    }

    // ------------------------------------------------------------------ instrument list

    @Test
    void theInstrumentListCountsSortsAndFiltersOnWhatEachUserSees() throws Exception {
        UUID b = stock("Sortlist Bravo", "SLB", isin("61"));
        UUID c = stock("Sortlist Charlie", "SLC", isin("62"));
        UUID d = stock("Sortlist Delta", "SLD", isin("63"));
        // Alice renames Delta to sort first and retypes Charlie.
        Map<String, Object> renameD = repointBody(alice, d, isin("63"));
        renameD.put("name", "Sortlist A-" + tag);
        alice.putJson("/api/v1/instruments/" + d, renameD, 200);
        Map<String, Object> retypeC = repointBody(alice, c, isin("62"));
        retypeC.put("type", "etf");
        alice.putJson("/api/v1/instruments/" + c, retypeC, 200);
        jdbc.update("UPDATE instruments SET yahoo_symbol = ? WHERE id = ?", "YSL" + tag + ".NS", b.toString());

        JsonNode page0 = alice.getJson("/api/v1/instruments?search=" + tag + "&size=2&page=0", 200);
        assertEquals(3, page0.get("totalElements").asLong());
        assertEquals(2, page0.get("totalPages").asInt());
        assertEquals(0, page0.get("page").asInt());
        assertEquals(2, page0.get("size").asInt());
        assertEquals(List.of(d.toString(), b.toString()), ids(page0.get("items")), "by the name she sees");
        assertEquals(List.of(c.toString()), ids(alice.getJson("/api/v1/instruments?search=" + tag + "&size=2&page=1", 200)
                .get("items")));
        assertEquals(List.of(c.toString(), b.toString(), d.toString()),
                ids(alice.getJson("/api/v1/instruments?search=" + tag + "&sort=name,desc", 200).get("items")));
        assertEquals(List.of(b.toString(), c.toString(), d.toString()),
                ids(bob.getJson("/api/v1/instruments?search=" + tag + "&sort=name", 200).get("items")), "Bob: catalog names");

        JsonNode aliceStocks = alice.getJson("/api/v1/instruments?search=" + tag + "&type=stock", 200);
        assertEquals(2, aliceStocks.get("totalElements").asLong(), "she retyped Charlie");
        assertEquals(List.of(d.toString(), b.toString()), ids(aliceStocks.get("items")));
        assertEquals(List.of(c.toString()), ids(alice.getJson("/api/v1/instruments?search=" + tag + "&type=etf", 200).get("items")));
        assertEquals(3, bob.getJson("/api/v1/instruments?search=" + tag + "&type=stock", 200).get("totalElements").asLong());

        assertEquals(List.of(d.toString()), ids(alice.getJson("/api/v1/instruments?search=A-" + tag, 200).get("items")),
                "her own name finds it");
        assertEquals(0, bob.getJson("/api/v1/instruments?search=a-" + tag.toLowerCase(Locale.ROOT), 200)
                .get("totalElements").asLong());
        assertEquals(List.of(b.toString()), ids(alice.getJson("/api/v1/instruments?search=ysl" + tag, 200).get("items")),
                "the Yahoo symbol is searched too");

        JsonNode none = alice.getJson("/api/v1/instruments?search=nothing-" + tag, 200);
        assertEquals(0, none.get("totalElements").asLong());
        assertEquals(0, none.get("totalPages").asInt());
        alice.getJson("/api/v1/instruments?sort=lastPrice", 400);
        alice.getJson("/api/v1/instruments?sort=name,sideways", 400);
    }
}
