package com.financeos.domain.instrument.corporateaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.investment.InvestmentService;
import com.financeos.domain.investment.TaxHarvestService;
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

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V100's data step ({@link SharedCorporateActionSplit}) proven against real persistence (H2): three
 * users on the OLD model (shared corporate actions every holder's engine read) have exactly the same
 * positions, average cost, realised lots, XIRR, summary, tax-harvest output, portfolio value and
 * position breakdowns after the shared rows are split into per-user copies.
 *
 * <p>"Before" is the old model's visibility: the old engine read every corporate action of an
 * instrument, whoever entered it, so each user is given a copy of every shared row (exactly what they
 * saw); "after" is the migration's selective copies. Equal figures prove the selection drops nothing
 * that mattered to anyone.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SharedCorporateActionSplitIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private UserRepository userRepository;
    @Autowired private InvestmentService investmentService;

    private final Map<String, ApiTestClient> clients = new LinkedHashMap<>();
    private final Map<String, UUID> userIds = new LinkedHashMap<>();
    private final Map<String, UUID> instruments = new LinkedHashMap<>();
    private final Map<String, UUID> brokers = new HashMap<>();
    private final Map<String, String> shared = new LinkedHashMap<>();
    private LocalDate today;
    private String tag;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        for (String u : List.of("A", "B", "C")) {
            String email = "v100-" + u.toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() + "@example.test";
            clients.put(u, ApiTestClient.signUp(mockMvc, mapper, email));
            userIds.put(u, userRepository.findByEmail(email).orElseThrow().getId());
        }
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        for (String id : shared.values()) {
            jdbc.update("DELETE FROM corporate_actions WHERE id = ?", id);
        }
        UserDataCleanup.deleteUsers(jdbc, userIds.values());
        for (UUID id : instruments.values()) {
            jdbc.update("DELETE FROM corporate_actions WHERE target_instrument_id = ?", id.toString());
        }
        UserDataCleanup.deleteInstruments(jdbc, instruments.values());
    }

    // ------------------------------------------------------------------ scenario

    private UUID instrument(String key) throws Exception {
        UUID id = clients.get("A").create("/api/v1/instruments", Map.of("type", "stock",
                "name", "V100 " + key + " " + tag, "symbol", "V" + key + tag.substring(0, 4), "exchange", "NSE",
                "isin", "INE" + tag + key));
        instruments.put(key, id);
        return id;
    }

    private UUID broker(String user) throws Exception {
        return brokers.computeIfAbsent(user, u -> {
            try {
                return clients.get(u).create("/api/v1/accounts", Map.of("type", "broker", "name", "V100 " + u,
                        "provider", "Zerodha", "clientId", "V100" + u, "cashBalance", 0, "financialPosition", "asset",
                        "excludeFromNetAsset", false));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void trade(String user, String instrument, String type, String qty, String price, int daysAgo) throws Exception {
        clients.get(user).create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker(user).toString(),
                "instrumentId", instruments.get(instrument).toString(), "type", type, "quantity", qty, "price", price,
                "tradeDate", today.minusDays(daysAgo).toString()));
    }

    /** A holding with no trades: where a demerger child / merger acquirer's seeded shares land. */
    private void emptyHolding(String user, String instrument) throws Exception {
        jdbc.update("INSERT INTO holdings (id, user_id, broker_account_id, instrument_id, notes, created_at) "
                        + "VALUES (?, ?, ?, ?, 'Created via corporate action', CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), userIds.get(user).toString(), broker(user).toString(),
                instruments.get(instrument).toString());
    }

    private void price(String instrument, int daysAgo, String close) {
        jdbc.update("INSERT INTO instrument_prices (id, instrument_id, as_of, close, source, created_at) "
                        + "VALUES (?, ?, ?, ?, 'YAHOO', CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), instruments.get(instrument).toString(),
                Date.valueOf(today.minusDays(daysAgo)), new BigDecimal(close));
    }

    /** A corporate action as the old model stored it: no owner. */
    private void sharedAction(String key, String instrument, String type, int from, int to, int daysAgo,
                              String target, String costPct, String cashInLieu) {
        sharedAction(UUID.randomUUID().toString(), key, instrument, type, from, to, daysAgo, target, costPct, cashInLieu);
    }

    /** {@link #sharedAction} with a chosen id (the split reads rows of one ex-date in id order). */
    private void sharedAction(String id, String key, String instrument, String type, int from, int to, int daysAgo,
                              String target, String costPct, String cashInLieu) {
        jdbc.update("INSERT INTO corporate_actions (id, user_id, instrument_id, type, ratio_from, ratio_to, ex_date, "
                        + "target_instrument_id, cost_allocation_pct, fractional_cash_in_lieu, notes, created_at) "
                        + "VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                id, instruments.get(instrument).toString(), type, from, to, Date.valueOf(today.minusDays(daysAgo)),
                target == null ? null : instruments.get(target).toString(),
                costPct == null ? null : new BigDecimal(costPct), cashInLieu == null ? null : new BigDecimal(cashInLieu),
                "shared " + key);
        shared.put(key, id);
    }

    private void buildOldModelScenario() throws Exception {
        for (String k : List.of("I1", "I2", "P", "K", "X", "Y", "I3", "I4", "Q", "R")) {
            instrument(k);
        }
        // A: an old holder of everything.
        trade("A", "I1", "buy", "10", "100", 300);
        trade("A", "I1", "sell", "3", "150", 100);
        trade("A", "I2", "buy", "6", "50", 200);
        trade("A", "P", "buy", "10", "200", 300);
        emptyHolding("A", "K");
        trade("A", "X", "buy", "7", "100", 400);
        emptyHolding("A", "Y");
        // B: bought I1 only after its split, P ON the demerger's ex-date, I3 after its (recent) split.
        trade("B", "I1", "buy", "5", "120", 150);
        trade("B", "I2", "buy", "4", "60", 200);
        trade("B", "P", "buy", "3", "210", 80);
        emptyHolding("B", "K");
        trade("B", "I3", "buy", "10", "100", 1);
        // A: a parent whose demerger and a split on its child share one ex-date.
        trade("A", "Q", "buy", "10", "100", 300);
        emptyHolding("A", "R");
        // C: bought the transferor only after its merger (still labelled "Merged into Y").
        trade("C", "X", "buy", "5", "100", 30);

        for (String k : List.of("I1", "I2", "P", "K", "Y")) {
            price(k, 1, "100");
            price(k, 0, "110");
        }
        price("I3", 5, "200");
        price("I3", 0, "100");

        sharedAction("split-I1", "I1", "split", 1, 2, 200, null, null, null);
        sharedAction("bonus-I1-ahead", "I1", "bonus", 1, 2, -10, null, null, null);
        sharedAction("bonus-I2", "I2", "bonus", 1, 2, 120, null, null, null);
        sharedAction("demerger-P", "P", "demerger", 3, 1, 80, "K", "25", "50");
        sharedAction("merger-X", "X", "merger", 2, 3, 60, "Y", "100", "30");
        sharedAction("split-K", "K", "split", 1, 2, 40, null, null, null);
        sharedAction("split-I3-recent", "I3", "split", 1, 2, 3, null, null, null);
        sharedAction("split-I4-nobody", "I4", "split", 1, 5, 30, null, null, null);
        // Same ex-date; the split's id sorts before the demerger's, yet the engine seeds R first.
        String low = tag.toLowerCase(Locale.ROOT);
        sharedAction("ffffffff-0000-0000-0000-" + low + "0000", "demerger-Q", "Q", "demerger", 1, 1, 90, "R", "25", null);
        sharedAction("00000000-0000-0000-0000-" + low + "0000", "split-R-same-day", "R", "split", 1, 2, 90, null, null, null);
    }

    // ------------------------------------------------------------------ snapshots

    /** Everything the lot engine feeds, for one user. */
    private Map<String, JsonNode> snapshot(String user) throws Exception {
        ApiTestClient client = clients.get(user);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        JsonNode positions = client.getJson("/api/v1/investments/positions", 200);
        out.put("positions", positions);
        out.put("summary", client.getJson("/api/v1/investments/summary", 200));
        int fy = TaxHarvestService.currentFy(today);
        out.put("harvest", client.getJson("/api/v1/investments/tax/harvest?size=100", 200));
        out.put("harvestPreviousFy", client.getJson("/api/v1/investments/tax/harvest?fy=" + (fy - 1), 200));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("mode", "raw");
        raw.put("columns", List.of("valueDate", "instrument", "value"));
        raw.put("filters", List.of());
        raw.put("sort", List.of());
        out.put("portfolioValue", client.postJson("/api/v1/reports/data",
                Map.of("type", "TABLE", "datasource", "portfolio_value", "definition", raw), 200).get("rows"));
        for (JsonNode p : positions.get("positions")) {
            String holding = p.get("holdingId").asText();
            out.put("breakdown " + holding, withoutNoOpHistory(client.getJson(
                    "/api/v1/report/datasource/positions/rows/" + holding + "/breakdown", 200)));
        }
        UserContext.setCurrentUserId(userIds.get(user));
        try {
            out.put("realizedLots", mapper.valueToTree(investmentService.getAllRealizedLots()));
        } finally {
            UserContext.clear();
        }
        return out;
    }

    /**
     * A position breakdown without its history lines that moved nothing on an empty position, mergers
     * excepted: the old model listed every holder's view of a split, bonus or demerger dated before they
     * ever held the instrument ("Split 1:2", quantity 0 → 0); a user who held only after it gets no copy,
     * so that no-op line is the one thing the split removes. A merger goes to every holder of the
     * transferor, so its "Merged into …" line stays compared even when it moved nothing. Figures, open
     * lots and every line that moved shares stay.
     */
    private static JsonNode withoutNoOpHistory(JsonNode breakdown) {
        JsonNode copy = breakdown.deepCopy();
        for (JsonNode section : copy.get("sections")) {
            if (!"history".equals(section.get("key").asText())) {
                continue;
            }
            com.fasterxml.jackson.databind.node.ArrayNode kept = mapperless();
            for (JsonNode row : section.get("table").get("rows")) {
                boolean noOp = row.get("quantityChange").decimalValue().signum() == 0
                        && row.get("quantityAfter").decimalValue().signum() == 0
                        && !row.get("event").asText().startsWith("Merged into");
                if (!noOp) {
                    ObjectNode r = row.deepCopy();
                    r.remove("id");
                    kept.add(r);
                }
            }
            ObjectNode table = (ObjectNode) section.get("table");
            table.set("rows", kept);
            table.remove("page");
        }
        return copy;
    }

    private static com.fasterxml.jackson.databind.node.ArrayNode mapperless() {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    private Map<String, Map<String, JsonNode>> snapshotAll() throws Exception {
        Map<String, Map<String, JsonNode>> out = new LinkedHashMap<>();
        for (String u : clients.keySet()) {
            out.put(u, snapshot(u));
        }
        return out;
    }

    /** The old model's visibility: every user reads every shared row. Returns the emulation rows' ids. */
    private List<String> emulateOldModel() {
        List<String> ids = new ArrayList<>();
        for (String sharedId : shared.values()) {
            for (UUID user : userIds.values()) {
                String id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO corporate_actions (id, user_id, instrument_id, type, ratio_from, ratio_to, ex_date, "
                                + "target_instrument_id, cost_allocation_pct, fractional_cash_in_lieu, notes, created_at) "
                                + "SELECT ?, ?, instrument_id, type, ratio_from, ratio_to, ex_date, target_instrument_id, "
                                + "cost_allocation_pct, fractional_cash_in_lieu, notes, created_at FROM corporate_actions "
                                + "WHERE id = ?", id, user.toString(), sharedId);
                ids.add(id);
            }
        }
        return ids;
    }

    private SharedCorporateActionSplit.Result runMigrationStep() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            return SharedCorporateActionSplit.run(c, today);
        }
    }

    private List<String> ownersOf(String key) {
        return jdbc.queryForList("SELECT u.email FROM corporate_actions ca JOIN users u ON u.id = ca.user_id "
                + "WHERE ca.notes = ? AND ca.instrument_id IN (" + inList() + ") ORDER BY u.email", String.class,
                "shared " + key).stream().map(this::userKey).toList();
    }

    private String inList() {
        return String.join(",", instruments.values().stream().map(id -> "'" + id + "'").toList());
    }

    private String userKey(String email) {
        return email.substring(5, 6).toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ the proof

    @Test
    void splittingSharedActionsChangesNoUsersFigures() throws Exception {
        buildOldModelScenario();

        List<String> emulation = emulateOldModel();
        Map<String, Map<String, JsonNode>> before = snapshotAll();
        for (String id : emulation) {
            jdbc.update("DELETE FROM corporate_actions WHERE id = ?", id);
        }

        SharedCorporateActionSplit.Result result = runMigrationStep();
        Map<String, Map<String, JsonNode>> after = snapshotAll();

        for (String u : before.keySet()) {
            assertEquals(before.get(u).keySet(), after.get(u).keySet(), u + "'s holdings");
            for (String part : before.get(u).keySet()) {
                assertEquals(before.get(u).get(part), after.get(u).get(part), u + " " + part);
            }
        }
        // The scenario exercises what the figures depend on (so the equality above is not vacuous).
        JsonNode bI3 = position(before.get("B"), "I3");
        assertEquals(0, BigDecimal.ZERO.compareTo(bI3.get("dayChange").decimalValue()), "B's I3 split rescales his day change");
        assertTrue(before.get("A").get("realizedLots").size() > 0, "A sold I1: realised lots are compared");
        assertEquals(0, new BigDecimal("10").compareTo(position(before.get("A"), "Y").get("quantity").decimalValue()),
                "floor(7 × 3 ÷ 2) seeded by the merger");
        assertEquals(0, new BigDecimal("34").compareTo(position(before.get("A"), "I1").get("quantity").decimalValue()),
                "(10 × 2 − 3) doubled by the bonus ahead");
        assertEquals(0, new BigDecimal("10").compareTo(position(before.get("B"), "I1").get("quantity").decimalValue()),
                "B: 5 bought after the split, doubled by the bonus ahead");
        assertEquals(0, new BigDecimal("20").compareTo(position(before.get("A"), "R").get("quantity").decimalValue()),
                "10 seeded into R, then doubled by the same-day split");
        assertEquals("V100 Y " + tag, position(after.get("C"), "X").get("mergedIntoName").asText(),
                "a holder who bought the transferor after the merger keeps its label");
        assertEquals(10, result.sharedRows());
    }

    private JsonNode position(Map<String, JsonNode> snapshot, String instrument) {
        for (JsonNode p : snapshot.get("positions").get("positions")) {
            if (p.get("instrument").get("id").asText().equals(instruments.get(instrument).toString())) {
                return p;
            }
        }
        throw new AssertionError("no position of " + instrument);
    }

    @Test
    void eachSharedRowGoesToTheUsersItAffectsAndOrphansAreDropped() throws Exception {
        buildOldModelScenario();

        SharedCorporateActionSplit.Result result = runMigrationStep();

        assertEquals(new SharedCorporateActionSplit.Result(10, 14, 1, 2, 4), result);
        assertEquals(List.of("A"), ownersOf("split-I1"), "B bought after the ex-date: never applied to his lots");
        assertEquals(List.of("A", "B"), ownersOf("bonus-I1-ahead"), "a future action: every current holder");
        assertEquals(List.of("A", "B"), ownersOf("bonus-I2"));
        assertEquals(List.of("A", "B"), ownersOf("demerger-P"), "B bought the parent on the ex-date itself");
        assertEquals(List.of("A", "C"), ownersOf("merger-X"), "every current holder of the transferor");
        assertEquals(List.of("A"), ownersOf("demerger-Q"));
        assertEquals(List.of("A"), ownersOf("split-R-same-day"), "the same-day seed counts whatever the row order");
        assertEquals(List.of("A", "B"), ownersOf("split-K"), "both received K from the demerger, without trades");
        assertEquals(List.of("B"), ownersOf("split-I3-recent"), "within the day-change window: every holder");
        assertEquals(List.of(), ownersOf("split-I4-nobody"));
        for (String id : shared.values()) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM corporate_actions WHERE id = ?", Integer.class, id),
                    "no shared row is left");
        }
        // Cash-in-lieu is kept on every copy as it was on the shared row.
        List<BigDecimal> cash = jdbc.queryForList("SELECT fractional_cash_in_lieu FROM corporate_actions "
                + "WHERE notes = 'shared demerger-P' AND instrument_id = ?", BigDecimal.class, instruments.get("P").toString());
        assertEquals(2, cash.size());
        cash.forEach(c -> assertEquals(0, new BigDecimal("50").compareTo(c), "each holder keeps the amount"));
        // New, stable ids.
        String aCopy = SharedCorporateActionSplit.copyId(shared.get("merger-X"), userIds.get("A").toString());
        assertNotEquals(shared.get("merger-X"), aCopy);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM corporate_actions WHERE id = ? AND user_id = ?",
                Integer.class, aCopy, userIds.get("A").toString()));

        // Idempotent: a second run finds no shared row and changes nothing.
        int rows = jdbc.queryForObject("SELECT COUNT(*) FROM corporate_actions WHERE instrument_id IN (" + inList() + ")",
                Integer.class);
        assertEquals(new SharedCorporateActionSplit.Result(0, 0, 0, 0, 0), runMigrationStep());
        assertEquals(rows, jdbc.queryForObject("SELECT COUNT(*) FROM corporate_actions WHERE instrument_id IN ("
                + inList() + ")", Integer.class));
    }

    @Test
    void aRunInterruptedAfterSomeCopiesInsertsNoDuplicates() throws Exception {
        buildOldModelScenario();
        String sharedId = shared.get("bonus-I2");
        String aCopy = SharedCorporateActionSplit.copyId(sharedId, userIds.get("A").toString());
        // A crash after A's copy of one row was written, before its shared row went.
        jdbc.update("INSERT INTO corporate_actions (id, user_id, instrument_id, type, ratio_from, ratio_to, ex_date, "
                + "target_instrument_id, cost_allocation_pct, fractional_cash_in_lieu, notes, created_at) "
                + "SELECT ?, ?, instrument_id, type, ratio_from, ratio_to, ex_date, target_instrument_id, "
                + "cost_allocation_pct, fractional_cash_in_lieu, notes, created_at FROM corporate_actions WHERE id = ?",
                aCopy, userIds.get("A").toString(), sharedId);

        SharedCorporateActionSplit.Result result = runMigrationStep();

        assertEquals(13, result.copiesInserted(), "A's existing copy is not inserted again");
        assertEquals(List.of("A", "B"), ownersOf("bonus-I2"));
        assertTrue(result.sharedRows() == 10 && result.rowsDropped() == 1, result.toString());
    }

    @Test
    void aParentHolderWithoutAChildHoldingKeepsLaterChildActionsForALaterImport() throws Exception {
        // The old model created the child holding only for whoever entered the demerger; B has none, but
        // his seeded shares (with the later split applied) appear as soon as a trade adds the holding.
        for (String k : List.of("P2", "K2")) {
            instrument(k);
        }
        trade("B", "P2", "buy", "10", "100", 300);
        sharedAction("demerger-P2", "P2", "demerger", 1, 1, 100, "K2", "25", null);
        sharedAction("split-K2", "K2", "split", 1, 2, 50, null, null, null);

        runMigrationStep();

        assertEquals(List.of("B"), ownersOf("split-K2"), "seeded into K2 before the split, holding row or not");
        trade("B", "K2", "buy", "1", "10", 10);
        BigDecimal k2 = BigDecimal.ZERO;
        for (JsonNode p : clients.get("B").getJson("/api/v1/investments/positions", 200).get("positions")) {
            if (p.get("instrument").get("id").asText().equals(instruments.get("K2").toString())) {
                k2 = k2.add(p.get("quantity").decimalValue());
            }
        }
        assertEquals(0, new BigDecimal("21").compareTo(k2), "10 seeded, doubled by the split, plus 1 bought: " + k2);
    }

    @Test
    void aMergerReachesAHolderWithoutTradesOrBuyingAfterItsExDate() throws Exception {
        for (String k : List.of("X3", "Y3")) {
            instrument(k);
        }
        trade("B", "X3", "buy", "5", "100", 10);
        emptyHolding("C", "X3");
        sharedAction("merger-X3", "X3", "merger", 1, 1, 30, "Y3", "100", null);

        runMigrationStep();

        assertEquals(List.of("B", "C"), ownersOf("merger-X3"));
    }
}
