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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repoint (identifier edit) onto an EXISTING instrument never changes the user's positions or cost
 * (H2, real persistence): it is refused when carrying the user's corporate actions would let one reach
 * lots it does not reach today — an action on the target while the source has lots, or one on the
 * source while the target has lots — and the refusal moves nothing. Moves no action can reach across
 * still go through, and the merge note says when merging two holdings' trades changed their figures.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RepointCorporateActionReachIntegrationTest {

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
        String a = "reach-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        String b = "reach-b-" + UUID.randomUUID() + "@example.test";
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
        UUID id = alice.create("/api/v1/instruments", Map.of("type", "stock", "name", name + " " + tag,
                "symbol", symbol + tag.substring(0, 4), "exchange", "NSE", "isin", isin));
        instrumentIds.add(id);
        return id;
    }

    private static UUID broker(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "broker", "name", name, "provider", "Zerodha",
                "clientId", "RCH" + name, "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private void trade(ApiTestClient client, UUID broker, UUID instrument, String type, String qty, String price,
                       int daysAgo) throws Exception {
        client.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", qty, "price", price,
                "tradeDate", today.minusDays(daysAgo).toString()));
    }

    private UUID split(ApiTestClient client, UUID instrument, int from, int to, int daysAgo) throws Exception {
        return client.create("/api/v1/instruments/" + instrument + "/corporate-actions", Map.of("type", "split",
                "ratioFrom", from, "ratioTo", to, "exDate", today.minusDays(daysAgo).toString()));
    }

    private UUID demerger(ApiTestClient client, UUID parent, UUID child, int daysAgo, int costPct) throws Exception {
        return client.create("/api/v1/instruments/" + parent + "/corporate-actions", Map.of("type", "demerger",
                "ratioFrom", 1, "ratioTo", 1, "exDate", today.minusDays(daysAgo).toString(),
                "targetInstrumentId", child.toString(), "costAllocationPct", costPct));
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

    private JsonNode repoint(ApiTestClient client, UUID id, String newIsin, int status) throws Exception {
        return client.putJson("/api/v1/instruments/" + id, repointBody(client, id, newIsin), status);
    }

    /** Every open position of {@code client}, without what names the instrument, keyed by holding id. */
    private Map<String, JsonNode> positions(ApiTestClient client) throws Exception {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode p : client.getJson("/api/v1/investments/positions", 200).get("positions")) {
            ObjectNode copy = p.deepCopy();
            copy.remove("instrument");
            out.put(p.get("holdingId").asText(), copy);
        }
        return out;
    }

    private BigDecimal quantity(ApiTestClient client, UUID instrument) throws Exception {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode p : client.getJson("/api/v1/investments/positions", 200).get("positions")) {
            if (p.get("instrument").get("id").asText().equals(instrument.toString())) {
                total = total.add(p.get("quantity").decimalValue());
            }
        }
        return total;
    }

    private int holdingsOf(UUID user, UUID instrument) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM holdings WHERE user_id = ? AND instrument_id = ?",
                Integer.class, user.toString(), instrument.toString());
    }

    private String instrumentOf(UUID action) {
        return jdbc.queryForObject("SELECT instrument_id FROM corporate_actions WHERE id = ?", String.class,
                action.toString());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " vs " + actual);
    }

    // ------------------------------------------------------------------ refused

    @Test
    void theUsersSplitOnTheTargetWouldReachTheMovedLotsSoTheMoveIsRefused() throws Exception {
        // Reviewer probe: S at broker Z, T at broker G with her own split on T.
        UUID s = stock("Src Row", "SRC", isin("S1"));
        UUID t = stock("Tgt Row", "TGT", isin("T1"));
        trade(alice, broker(alice, "Z"), s, "buy", "10", "100", 300);
        trade(alice, broker(alice, "G"), t, "buy", "4", "100", 300);
        UUID hers = split(alice, t, 1, 2, 50);
        Map<String, JsonNode> before = positions(alice);

        JsonNode refused = repoint(alice, s, isin("T1"), 400);

        String message = refused.get("message").asText();
        assertTrue(message.contains("split of Tgt Row " + tag + " 1:2"), message);
        assertTrue(message.contains("would also apply to the shares moved from Src Row " + tag), message);
        assertEquals(before, positions(alice), "nothing moved");
        assertEquals(1, holdingsOf(aliceId, s));
        assertEquals(t.toString(), instrumentOf(hers));
        assertDecimal("10", quantity(alice, s));
        assertDecimal("8", quantity(alice, t));
    }

    @Test
    void theUsersSplitOnTheSourceWouldReachTheTargetsLotsSoTheMoveIsRefused() throws Exception {
        UUID s = stock("Split Src", "SPS", isin("S2"));
        UUID t = stock("Held Tgt", "HTG", isin("T2"));
        trade(alice, broker(alice, "Z"), s, "buy", "10", "100", 300);
        trade(alice, broker(alice, "G"), t, "buy", "4", "100", 300);
        UUID hers = split(alice, s, 1, 3, 50);
        Map<String, JsonNode> before = positions(alice);

        JsonNode refused = repoint(alice, s, isin("T2"), 400);

        String message = refused.get("message").asText();
        assertTrue(message.contains("split of Split Src " + tag + " 1:3"), message);
        assertTrue(message.contains("would also apply to the Held Tgt " + tag + " shares you already hold"), message);
        assertEquals(before, positions(alice));
        assertEquals(s.toString(), instrumentOf(hers));
    }

    @Test
    void aBonusInTheRefusalIsNamedNewToHeld() throws Exception {
        // A 1:1 bonus is stored held → held-after (1 → 2); the refusal names it the usual way, 1:1.
        UUID s = stock("Bonus Src", "BNS", isin("S9"));
        UUID t = stock("Bonus Tgt", "BNT", isin("T9"));
        trade(alice, broker(alice, "Z"), s, "buy", "10", "100", 300);
        trade(alice, broker(alice, "G"), t, "buy", "4", "100", 300);
        alice.create("/api/v1/instruments/" + t + "/corporate-actions", Map.of("type", "bonus",
                "ratioFrom", 1, "ratioTo", 2, "exDate", today.minusDays(50).toString()));
        Map<String, JsonNode> before = positions(alice);

        String message = repoint(alice, s, isin("T9"), 400).get("message").asText();

        assertTrue(message.contains("bonus on Bonus Tgt " + tag + " 1:1 on "), message);
        assertTrue(message.contains("would also apply to the shares moved from Bonus Src " + tag), message);
        assertEquals(before, positions(alice), "nothing moved");
        assertDecimal("8", quantity(alice, t));
    }

    @Test
    void aDemergerOfTheSourceWouldFindTheTargetsLotsSoTheMoveIsRefused() throws Exception {
        // Carried, the demerger's parent would be T: its lots at the same broker would seed more child shares.
        UUID s = stock("Dem Parent", "DMP", isin("S3"));
        UUID t = stock("Dem Other", "DMO", isin("T3"));
        UUID k = stock("Dem Child", "DMK", isin("K3"));
        UUID z = broker(alice, "Z");
        trade(alice, z, s, "buy", "10", "100", 300);
        trade(alice, z, t, "buy", "6", "100", 300);
        demerger(alice, s, k, 100, 20);
        assertDecimal("10", quantity(alice, k));

        JsonNode refused = repoint(alice, s, isin("T3"), 400);

        assertTrue(refused.get("message").asText().contains("demerger of Dem Parent " + tag + " into Dem Child " + tag),
                refused.toString());
        assertDecimal("10", quantity(alice, k));
        assertDecimal("10", quantity(alice, s));
    }

    // ------------------------------------------------------------------ allowed

    @Test
    void anotherUsersSplitOnTheTargetDoesNotBlockAndChangesNothing() throws Exception {
        UUID s = stock("Mine Src", "MSR", isin("S4"));
        UUID t = stock("Their Tgt", "TTG", isin("T4"));
        trade(alice, broker(alice, "Z"), s, "buy", "10", "100", 300);
        trade(alice, broker(alice, "G"), t, "buy", "4", "100", 300);
        UUID bobsBroker = broker(bob, "B");
        trade(bob, bobsBroker, t, "buy", "1", "100", 300);
        split(bob, t, 1, 2, 50);

        JsonNode moved = repoint(alice, s, isin("T4"), 200);

        assertEquals(t.toString(), moved.get("id").asText());
        assertDecimal("14", quantity(alice, t));
        assertDecimal("2", quantity(bob, t));
    }

    @Test
    void sharesSeededIntoTheSourceMoveOntoAHeldTargetUnchanged() throws Exception {
        // Her demerger only SEEDS shares into S; no action of hers applies to S or T, so nothing reaches across.
        UUID p = stock("Seed Parent", "SDP", isin("P5"));
        UUID s = stock("Seed Child", "SDC", isin("S5"));
        UUID t = stock("Seed Target", "SDT", isin("T5"));
        UUID z = broker(alice, "Z");
        trade(alice, z, p, "buy", "10", "100", 300);
        UUID d = demerger(alice, p, s, 100, 25);
        trade(alice, broker(alice, "G"), t, "buy", "4", "100", 300);
        BigDecimal parentBefore = quantity(alice, p);
        assertDecimal("10", quantity(alice, s));

        JsonNode moved = repoint(alice, s, isin("T5"), 200);

        assertEquals(t.toString(), moved.get("id").asText());
        assertDecimal("14", quantity(alice, t));
        assertDecimal(parentBefore.toPlainString(), quantity(alice, p));
        assertEquals(t.toString(), jdbc.queryForObject("SELECT target_instrument_id FROM corporate_actions WHERE id = ?",
                String.class, d.toString()));
    }

    @Test
    void theUsersSplitOnATargetSheHasNoLotsOfMovesNothingItCouldReach() throws Exception {
        // Her split on S carries to T, which she has never traded (and nothing seeds into it).
        UUID s = stock("Lone Src", "LSR", isin("S6"));
        UUID t = stock("Lone Tgt", "LTG", isin("T6"));
        trade(alice, broker(alice, "Z"), s, "buy", "10", "100", 300);
        UUID hers = split(alice, s, 1, 2, 50);
        Map<String, JsonNode> before = positions(alice);

        JsonNode moved = repoint(alice, s, isin("T6"), 200);

        assertEquals(t.toString(), moved.get("id").asText());
        assertEquals(before, positions(alice), "same quantity and cost on T");
        assertEquals(t.toString(), instrumentOf(hers));
    }

    // ------------------------------------------------------------------ merge note

    @Test
    void mergingIntoAnEarlierHoldingWhenOnlyTheSourceSoldSaysRealisedGainsDiffer() throws Exception {
        // T's earlier buy now comes first in FIFO, so S's sell consumes it: cost and realised gains move.
        UUID s = stock("Fifo Src", "FSR", isin("S7"));
        UUID t = stock("Fifo Tgt", "FTG", isin("T7"));
        UUID z = broker(alice, "Z");
        trade(alice, z, t, "buy", "10", "200", 90);
        trade(alice, z, s, "buy", "10", "100", 80);
        trade(alice, z, s, "sell", "4", "150", 60);

        JsonNode moved = repoint(alice, s, isin("T7"), 200);

        assertTrue(moved.get("mergedHoldings").asBoolean());
        assertTrue(moved.get("mergeNote").asText().contains("realised gains"), moved.toString());
        assertDecimal("16", quantity(alice, t));
    }

    @Test
    void mergingWhenFifoMatchesTheSameLotsSaysNothing() throws Exception {
        // Both sold, but S was fully sold before T's buy: the merged history consumes the same lots.
        UUID s = stock("Same Src", "SSR", isin("S8"));
        UUID t = stock("Same Tgt", "STG", isin("T8"));
        UUID z = broker(alice, "Z");
        trade(alice, z, s, "buy", "10", "100", 90);
        trade(alice, z, s, "sell", "10", "150", 80);
        trade(alice, z, t, "buy", "10", "200", 70);
        trade(alice, z, t, "sell", "2", "210", 60);

        JsonNode moved = repoint(alice, s, isin("T8"), 200);

        assertTrue(moved.get("mergedHoldings").asBoolean());
        assertTrue(moved.get("mergeNote").isNull(), moved.toString());
        assertDecimal("8", quantity(alice, t));
    }
}
