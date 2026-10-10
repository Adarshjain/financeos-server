package com.financeos.api.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.investment.dto.ImportCommitRequest;
import com.financeos.api.investment.dto.ImportCommitResponse;
import com.financeos.api.investment.dto.ImportPreviewResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.investment.imports.ImportService;
import com.financeos.domain.investment.imports.ImportSource;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing an instrument is per user account, against real persistence (H2) with two users holding
 * the same catalog instrument: Alice's display edits, identifier edits, manual prices, aliases and
 * resets never change what Bob sees, and every surface that names an instrument shows Alice her own
 * view.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InstrumentEditsPerUserIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportService importService;

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
        String a = "uio-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        String b = "uio-b-" + UUID.randomUUID() + "@example.test";
        bob = ApiTestClient.signUp(mockMvc, mapper, b);
        bobId = userRepository.findByEmail(b).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId, bobId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    // ------------------------------------------------------------------ helpers

    private String isin(String suffix) {
        return "INE" + tag + suffix;
    }

    private UUID stock(String name, String symbol, String isin, String yahoo) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "stock");
        body.put("name", name + " " + tag);
        body.put("symbol", symbol + tag.substring(0, 4));
        body.put("exchange", "NSE");
        body.put("isin", isin);
        body.put("yahooSymbol", yahoo);
        UUID id = alice.create("/api/v1/instruments", body);
        instrumentIds.add(id);
        return id;
    }

    private static UUID broker(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "broker", "name", name, "provider", "Zerodha",
                "clientId", "UIO1", "cashBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static UUID trade(ApiTestClient client, UUID broker, UUID instrument, String type, String qty, String price,
                              LocalDate date) throws Exception {
        return client.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrument.toString(), "type", type, "quantity", qty, "price", price,
                "tradeDate", date.toString()));
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

    private JsonNode edit(ApiTestClient client, UUID id, Map<String, Object> changes, int status) throws Exception {
        JsonNode current = client.getJson("/api/v1/instruments/" + id, 200);
        Map<String, Object> body = new HashMap<>();
        for (String f : List.of("type", "name", "symbol", "exchange", "isin", "amfiCode", "yahooSymbol", "currency")) {
            JsonNode v = current.get(f);
            body.put(f, v == null || v.isNull() ? null : v.asText());
        }
        body.putAll(changes);
        return client.putJson("/api/v1/instruments/" + id, body, status);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private void feedPrice(UUID instrument, LocalDate asOf, String close) {
        jdbc.update("INSERT INTO instrument_prices (id, instrument_id, as_of, close, source, created_at) "
                        + "VALUES (?, ?, ?, ?, 'YAHOO', CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), instrument.toString(), Date.valueOf(asOf), new BigDecimal(close));
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertDecimal(expected, actual, "");
    }

    private static void assertDecimal(String expected, JsonNode actual, String why) {
        assertTrue(actual != null && actual.isNumber(), why + ": expected " + expected + " but was " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), why + ": " + expected + " vs " + actual);
    }

    private static List<String> reportColumn(ApiTestClient client, String datasource, String column) throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("mode", "raw");
        raw.put("columns", List.of(column));
        raw.put("filters", List.of());
        raw.put("sort", List.of());
        JsonNode table = client.postJson("/api/v1/reports/data",
                Map.of("type", "TABLE", "datasource", datasource, "definition", raw), 200);
        List<String> out = new ArrayList<>();
        table.get("rows").forEach(r -> out.add(r.get(column).asText()));
        return out;
    }

    private static JsonNode harvestLot(ApiTestClient client, UUID instrument) throws Exception {
        for (JsonNode lot : client.getJson("/api/v1/investments/tax/harvest", 200).get("openLots").get("items")) {
            if (lot.get("instrumentId").asText().equals(instrument.toString())) {
                return lot;
            }
        }
        throw new AssertionError("no open lot of " + instrument);
    }

    /** Today's portfolio value (the user holds one instrument in these tests). */
    private String lastPortfolioValue(ApiTestClient client) throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("mode", "raw");
        raw.put("columns", List.of("valueDate", "value"));
        raw.put("filters", List.of());
        raw.put("sort", List.of(Map.of("key", "valueDate", "direction", "desc")));
        JsonNode rows = client.postJson("/api/v1/reports/data",
                Map.of("type", "TABLE", "datasource", "portfolio_value", "definition", raw), 200).get("rows");
        assertEquals(today.toString(), rows.get(0).get("valueDate").asText());
        return rows.get(0).get("value").decimalValue().setScale(2).toPlainString();
    }

    // ------------------------------------------------------------------ display edits

    @Test
    void aDisplayEditIsTheEditorsOwnEverywhereTheyLook() throws Exception {
        UUID s = stock("Display Co", "DSP", isin("01"), null);
        String catalogName = "Display Co " + tag;
        UUID aliceBroker = broker(alice, "Alice Broker");
        UUID bobBroker = broker(bob, "Bob Broker");
        trade(alice, aliceBroker, s, "buy", "10", "100", today.minusDays(60));
        trade(alice, aliceBroker, s, "sell", "4", "120", today.minusDays(5));
        trade(bob, bobBroker, s, "buy", "5", "100", today.minusDays(60));
        alice.create("/api/v1/investments/dividends", Map.of("brokerAccountId", aliceBroker.toString(),
                "instrumentId", s.toString(), "type", "dividend", "amount", "12", "payDate", today.minusDays(3).toString()));
        alice.create("/api/v1/investments/sips", Map.of("brokerAccountId", aliceBroker.toString(), "instrumentId",
                s.toString(), "amount", "1000", "frequency", "monthly", "dayOfMonth", 5,
                "startDate", today.minusDays(90).toString()));

        String myName = "Renamed" + tag;
        JsonNode edited = edit(alice, s, Map.of("name", myName, "symbol", "MYD", "exchange", "BSE", "currency", "USD"), 200);

        assertEquals(s.toString(), edited.get("id").asText(), "a display edit keeps the instrument");
        assertEquals(myName, edited.get("name").asText());
        assertEquals("MYD", edited.get("symbol").asText());
        assertEquals("BSE", edited.get("exchange").asText());
        assertEquals("USD", edited.get("currency").asText());
        assertTrue(edited.get("overridden").asBoolean());
        assertEquals(List.of("name", "symbol", "exchange", "currency"), texts(edited.get("overriddenFields")));

        JsonNode bobsView = bob.getJson("/api/v1/instruments/" + s, 200);
        assertEquals(catalogName, bobsView.get("name").asText());
        assertEquals("NSE", bobsView.get("exchange").asText());
        assertEquals("INR", bobsView.get("currency").asText());
        assertFalse(bobsView.get("overridden").asBoolean());
        assertEquals(0, bobsView.get("overriddenFields").size());
        assertEquals(catalogName, jdbc.queryForObject("SELECT name FROM instruments WHERE id = ?", String.class,
                s.toString()), "the shared row is untouched");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM instrument_aliases WHERE instrument_id = ?",
                Integer.class, s.toString()), "a display edit writes no alias");

        // Positions, trades, dividends, SIPs, corporate actions.
        assertEquals(myName, position(alice, s).get("instrument").get("name").asText());
        assertEquals("MYD", position(alice, s).get("instrument").get("symbol").asText());
        assertEquals(catalogName, position(bob, s).get("instrument").get("name").asText());
        JsonNode aliceTrades = alice.getJson("/api/v1/investments/transactions?instrumentId=" + s, 200).get("content");
        assertEquals(myName, aliceTrades.get(0).get("instrument").get("name").asText());
        JsonNode bobTrades = bob.getJson("/api/v1/investments/transactions?instrumentId=" + s, 200).get("content");
        assertEquals(catalogName, bobTrades.get(0).get("instrument").get("name").asText());
        assertEquals(myName, alice.getJson("/api/v1/investments/dividends", 200).get("content").get(0)
                .get("instrumentName").asText());
        assertEquals(myName, alice.getJson("/api/v1/investments/sips", 200).get(0).get("instrumentName").asText());
        alice.create("/api/v1/instruments/" + s + "/corporate-actions", Map.of("type", "split", "ratioFrom", 1,
                "ratioTo", 2, "exDate", today.minusDays(400).toString()));
        assertEquals(myName, alice.getJson("/api/v1/instruments/" + s + "/corporate-actions", 200).get(0)
                .get("instrumentName").asText());
        assertEquals(catalogName, bob.getJson("/api/v1/instruments/" + s + "/corporate-actions", 200).get(0)
                .get("instrumentName").asText());

        // Search: the catalog by Alice's name, and her trades by it.
        List<String> aliceHits = new ArrayList<>();
        alice.getJson("/api/v1/instruments?search=" + myName, 200)
                .forEach(i -> aliceHits.add(i.get("id").asText()));
        assertEquals(List.of(s.toString()), aliceHits);
        assertEquals(0, bob.getJson("/api/v1/instruments?search=" + myName, 200).size());
        assertEquals(s.toString(), bob.getJson("/api/v1/instruments?search=" + tag, 200).get(0).get("id").asText());
        assertEquals(2, alice.getJson("/api/v1/investments/transactions?search=" + myName.toLowerCase(Locale.ROOT), 200)
                .get("totalElements").asInt());
        assertEquals(0, bob.getJson("/api/v1/investments/transactions?search=" + myName, 200)
                .get("totalElements").asInt());
        assertEquals(1, bob.getJson("/api/v1/investments/transactions?search=" + tag, 200)
                .get("totalElements").asInt(), "the catalog name still finds Bob's trade");
        assertEquals(2, alice.getJson("/api/v1/investments/transactions?search=" + tag, 200)
                .get("totalElements").asInt(), "and the catalog name finds Alice's");

        // Reports and breakdowns.
        assertTrue(reportColumn(alice, "investment_trades", "instrument").stream().allMatch(myName::equals));
        assertTrue(reportColumn(bob, "investment_trades", "instrument").stream().allMatch(catalogName::equals));
        assertTrue(reportColumn(alice, "dividends", "instrument").stream().allMatch(myName::equals));
        assertTrue(reportColumn(alice, "positions", "instrument").contains(myName));
        assertTrue(reportColumn(alice, "realized_lots", "instrument").contains(myName));
        assertTrue(reportColumn(alice, "portfolio_value", "instrument").contains(myName));
        assertTrue(reportColumn(bob, "positions", "instrument").contains(catalogName));
        assertTrue(texts(alice.getJson("/api/v1/report/datasource/investment_trades/values", 200).get("values")
                .get("instrument")).contains(myName));
        assertFalse(texts(bob.getJson("/api/v1/report/datasource/investment_trades/values", 200).get("values")
                .get("instrument")).contains(myName));
        boolean inNetWorth = false;
        for (JsonNode section : alice.getJson("/api/v1/report/datasource/net_worth/rows/" + aliceBroker + "/breakdown", 200)
                .get("sections")) {
            if ("holdings".equals(section.get("key").asText())) {
                for (JsonNode row : section.get("table").get("rows")) {
                    inNetWorth |= myName.equals(row.get("instrument").asText());
                }
            }
        }
        assertTrue(inNetWorth, "the net-worth holdings breakdown names it as Alice does");
        String holding = position(alice, s).get("holdingId").asText();
        assertEquals(myName, alice.getJson("/api/v1/report/datasource/positions/rows/" + holding + "/breakdown", 200)
                .get("title").asText());
        JsonNode harvest = alice.getJson("/api/v1/investments/tax/harvest", 200).get("openLots").get("items");
        boolean found = false;
        for (JsonNode lot : harvest) {
            if (lot.get("instrumentId").asText().equals(s.toString())) {
                assertEquals(myName, lot.get("instrument").asText());
                found = true;
            }
        }
        assertTrue(found, "an open lot of the edited instrument");
    }

    @Test
    void aTypeEditReclassifiesForTheEditorOnly() throws Exception {
        UUID fund = alice.create("/api/v1/instruments", Map.of("type", "mutual_fund",
                "name", "Typed Liquid Fund " + tag));
        instrumentIds.add(fund);
        trade(alice, broker(alice, "A"), fund, "buy", "10", "10", today.minusDays(40));
        trade(bob, broker(bob, "B"), fund, "buy", "10", "10", today.minusDays(40));
        assertEquals("DEBT", bob.getJson("/api/v1/instruments/" + fund, 200).get("assetClass").asText());

        JsonNode mine = edit(alice, fund, Map.of("type", "stock"), 200);

        assertEquals("stock", mine.get("type").asText());
        assertEquals("EQUITY", mine.get("assetClass").asText());
        assertEquals("EQUITY_ORIENTED", mine.get("taxClass").asText());
        assertEquals(List.of("type"), texts(mine.get("overriddenFields")));
        JsonNode alicePosition = position(alice, fund);
        assertEquals("stock", alicePosition.get("instrument").get("type").asText());
        assertEquals("EQUITY", alicePosition.get("assetClass").asText());
        JsonNode bobPosition = position(bob, fund);
        assertEquals("mutual_fund", bobPosition.get("instrument").get("type").asText());
        assertEquals("DEBT", bobPosition.get("assetClass").asText());
        assertEquals("mutual_fund", jdbc.queryForObject("SELECT type FROM instruments WHERE id = ?", String.class,
                fund.toString()));
        // The summary groups by the reader's type.
        boolean aliceStock = false;
        for (JsonNode t : alice.getJson("/api/v1/investments/summary", 200).get("byInstrumentType")) {
            aliceStock |= "stock".equals(t.get("type").asText());
        }
        assertTrue(aliceStock);
        assertEquals(List.of("stock"), reportColumn(alice, "investment_trades", "instrumentType"));
        assertEquals(List.of("mutual_fund"), reportColumn(bob, "investment_trades", "instrumentType"));
    }

    @Test
    void anEditBackToTheCatalogStoresNothing() throws Exception {
        UUID s = stock("Same Co", "SAM", isin("02"), null);
        JsonNode same = edit(alice, s, Map.of("symbol", ("sam" + tag.substring(0, 4)).toLowerCase(Locale.ROOT),
                "exchange", "nse", "currency", ""), 200);
        assertFalse(same.get("overridden").asBoolean());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_instrument_overrides WHERE user_id = ?",
                Integer.class, aliceId.toString()));
    }

    @Test
    void anAssetClassPatchAndDisplayEditsCoexistAndResetClearsBoth() throws Exception {
        UUID fund = alice.create("/api/v1/instruments", Map.of("type", "mutual_fund", "name", "Reset Liquid Fund " + tag));
        instrumentIds.add(fund);
        bob.patchJson("/api/v1/instruments/" + fund, "{\"assetClass\":\"HYBRID\"}", 200);

        alice.patchJson("/api/v1/instruments/" + fund, "{\"assetClass\":\"GOLD\"}", 200);
        JsonNode both = edit(alice, fund, Map.of("name", "Alice Fund"), 200);
        assertEquals(List.of("name", "assetClass"), texts(both.get("overriddenFields")));
        assertEquals("GOLD", both.get("assetClass").asText());

        JsonNode classCleared = alice.patchJson("/api/v1/instruments/" + fund, "{\"assetClass\":null}", 200);
        assertEquals(List.of("name"), texts(classCleared.get("overriddenFields")), "the rename stays");
        assertEquals("DEBT", classCleared.get("assetClass").asText());

        alice.patchJson("/api/v1/instruments/" + fund, "{\"assetClass\":\"GOLD\"}", 200);
        JsonNode reset = alice.deleteJson("/api/v1/instruments/" + fund + "/overrides", 200);
        assertEquals("Reset Liquid Fund " + tag, reset.get("name").asText());
        assertEquals("DEBT", reset.get("assetClass").asText());
        assertFalse(reset.get("overridden").asBoolean());
        assertEquals("Reset Liquid Fund " + tag, alice.getJson("/api/v1/instruments/" + fund, 200).get("name").asText());

        JsonNode bobs = bob.getJson("/api/v1/instruments/" + fund, 200);
        assertEquals("HYBRID", bobs.get("assetClass").asText(), "Alice's reset leaves Bob's own override");
        assertEquals(List.of("assetClass"), texts(bobs.get("overriddenFields")));

        alice.deleteJson("/api/v1/instruments/" + fund + "/overrides", 200);
        alice.deleteJson("/api/v1/instruments/" + UUID.randomUUID() + "/overrides", 404);
    }

    // ------------------------------------------------------------------ identifier edits

    @Test
    void anIdentifierEditMovesOnlyTheEditorToTheInstrumentWithThoseIdentifiers() throws Exception {
        UUID s = stock("Feed Co", "OLD", isin("03"), "OLD" + tag + ".NS");
        String oldSymbol = "OLD" + tag.substring(0, 4);
        UUID aliceBroker = broker(alice, "Alice Broker");
        trade(alice, aliceBroker, s, "buy", "10", "100", today.minusDays(30));
        trade(bob, broker(bob, "Bob Broker"), s, "buy", "3", "100", today.minusDays(30));
        alice.create("/api/v1/investments/dividends", Map.of("brokerAccountId", aliceBroker.toString(),
                "instrumentId", s.toString(), "type", "dividend", "amount", "7", "payDate", today.minusDays(2).toString()));
        alice.create("/api/v1/investments/sips", Map.of("brokerAccountId", aliceBroker.toString(), "instrumentId",
                s.toString(), "amount", "500", "frequency", "monthly", "dayOfMonth", 3,
                "startDate", today.minusDays(60).toString()));
        alice.postJson("/api/v1/instruments/" + s + "/price", Map.of("price", 130, "asOf", today.minusDays(1).toString()), 200);
        alice.patchJson("/api/v1/instruments/" + s, "{\"assetClass\":\"INTERNATIONAL\"}", 200);
        jdbc.update("INSERT INTO trade_settlement_classifications (id, user_id, broker_account_id, holding_id, instrument_id, "
                        + "trade_date, intraday_qty, intraday_buy_value, intraday_sell_value, created_at) "
                        + "VALUES (?, ?, ?, NULL, ?, ?, 1, 10, 11, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), aliceId.toString(), aliceBroker.toString(), s.toString(),
                Date.valueOf(today.minusDays(20)));

        String newIsin = isin("04");
        JsonNode moved = edit(alice, s, Map.of("isin", newIsin, "yahooSymbol", "NEW" + tag + ".NS"), 200);
        UUID t = UUID.fromString(moved.get("id").asText());
        instrumentIds.add(t);

        assertNotEquals(s, t, "an identifier edit lands on another catalog instrument");
        assertEquals(newIsin, moved.get("isin").asText());
        // Forced edit: an unchanged display value is no longer carried over as an override. The ticker
        // stays the source's in the catalog (unique), so the new row has none until she types one.
        assertTrue(moved.get("symbol").isNull(), "the ticker she left as it was is not pinned on the new row");
        assertEquals("NSE", moved.get("exchange").asText(), "the new row has the source's exchange");
        assertEquals(List.of("assetClass"), texts(moved.get("overriddenFields")),
                "only the asset-class override moved along");
        assertNull(jdbc.queryForObject("SELECT symbol FROM instruments WHERE id = ?", String.class, t.toString()));
        assertEquals("Feed Co " + tag, moved.get("name").asText(), "the new row has the source's catalog name");
        assertEquals("INTERNATIONAL", moved.get("assetClass").asText(), "the asset-class override moved along");
        assertEquals(isin("03"), jdbc.queryForObject("SELECT isin FROM instruments WHERE id = ?", String.class,
                s.toString()), "the shared source row keeps its identifiers");

        assertTrue(positions(alice, s).isEmpty());
        assertDecimal("10", position(alice, t).get("quantity"));
        assertDecimal("130", position(alice, t).get("lastPrice"), "Alice's manual price moved along");
        assertDecimal("3", position(bob, s).get("quantity"));
        assertTrue(positions(bob, t).isEmpty());
        assertEquals(t.toString(), alice.getJson("/api/v1/investments/dividends", 200).get("content").get(0)
                .get("instrumentId").asText());
        assertEquals(t.toString(), alice.getJson("/api/v1/investments/sips", 200).get(0).get("instrumentId").asText());
        assertEquals(0, alice.getJson("/api/v1/instruments/" + s + "/prices", 200).size());
        JsonNode tPrices = alice.getJson("/api/v1/instruments/" + t + "/prices", 200);
        assertEquals(1, tPrices.size());
        assertTrue(tPrices.get(0).get("editable").asBoolean());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM trade_settlement_classifications WHERE user_id = ? "
                + "AND instrument_id = ?", Integer.class, aliceId.toString(), t.toString()));
        assertEquals(t.toString(), jdbc.queryForObject("SELECT instrument_id FROM instrument_aliases WHERE user_id = ? "
                + "AND old_symbol = ?", String.class, aliceId.toString(), oldSymbol), "Alice's imports of the old symbol");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM instrument_aliases WHERE user_id IS NULL "
                + "AND instrument_id IN (?, ?)", Integer.class, s.toString(), t.toString()), "no catalog alias");
        assertEquals("INTERNATIONAL", alice.getJson("/api/v1/instruments/" + t, 200).get("assetClass").asText());
        assertFalse(alice.getJson("/api/v1/instruments/" + s, 200).get("overridden").asBoolean());
        assertTrue(bob.getJson("/api/v1/instruments/" + s, 200).get("lastPrice").isNull(),
                "Alice's manual price was never Bob's");
    }

    @Test
    void anIdentifierEditOntoAHeldInstrumentMergesTheHoldingsPerBroker() throws Exception {
        UUID s = stock("Merge From", "MFR", isin("05"), null);
        UUID t = stock("Merge Into", "MIN", isin("06"), null);
        UUID one = broker(alice, "One");
        UUID two = broker(alice, "Two");
        trade(alice, one, s, "buy", "10", "100", today.minusDays(50));
        trade(alice, one, t, "buy", "5", "200", today.minusDays(40));
        trade(alice, two, s, "buy", "2", "100", today.minusDays(30));
        alice.create("/api/v1/investments/dividends", Map.of("brokerAccountId", one.toString(),
                "instrumentId", s.toString(), "type", "dividend", "amount", "4", "payDate", today.minusDays(10).toString()));
        String sHolding = holdingId(one, s);
        String tHolding = holdingId(one, t);

        JsonNode moved = edit(alice, s, Map.of("isin", isin("06")), 200);

        assertEquals(t.toString(), moved.get("id").asText());
        List<JsonNode> onT = positions(alice, t);
        assertEquals(2, onT.size(), "one holding per broker");
        for (JsonNode p : onT) {
            if (p.get("brokerAccountId").asText().equals(one.toString())) {
                assertEquals(tHolding, p.get("holdingId").asText(), "merged into the existing holding at broker One");
                assertDecimal("15", p.get("quantity"), "both buys at broker One");
                assertDecimal("4", p.get("dividends"), "the dividend came along");
            } else {
                assertDecimal("2", p.get("quantity"), "moved at broker Two");
            }
        }
        assertTrue(positions(alice, s).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM holdings WHERE id = ?", Integer.class, sHolding));
        assertEquals(3, alice.getJson("/api/v1/investments/transactions?instrumentId=" + t, 200)
                .get("totalElements").asInt());
    }

    private String holdingId(UUID broker, UUID instrument) {
        return jdbc.queryForObject("SELECT id FROM holdings WHERE user_id = ? AND broker_account_id = ? "
                + "AND instrument_id = ?", String.class, aliceId.toString(), broker.toString(), instrument.toString());
    }

    private void classification(UUID broker, String holding, UUID instrument, LocalDate day, String qty) {
        jdbc.update("INSERT INTO trade_settlement_classifications (id, user_id, broker_account_id, holding_id, "
                        + "instrument_id, trade_date, intraday_qty, intraday_buy_value, intraday_sell_value, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 10, 11, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), aliceId.toString(), broker.toString(), holding, instrument.toString(),
                Date.valueOf(day), new BigDecimal(qty));
    }

    @Test
    void intradayClassificationsFollowAndADayBothHaveIsAddedUp() throws Exception {
        UUID s = stock("Intraday From", "IFR", isin("11"), null);
        UUID t = stock("Intraday Into", "IIN", isin("12"), null);
        UUID one = broker(alice, "One");
        trade(alice, one, s, "buy", "10", "100", today.minusDays(50));
        trade(alice, one, t, "buy", "5", "200", today.minusDays(40));
        String sHolding = holdingId(one, s);
        String tHolding = holdingId(one, t);
        LocalDate shared = today.minusDays(20);
        LocalDate onlySource = today.minusDays(19);
        classification(one, sHolding, s, shared, "2");
        classification(one, tHolding, t, shared, "3");
        classification(one, sHolding, s, onlySource, "1");
        classification(one, null, s, today.minusDays(18), "1");

        edit(alice, s, Map.of("isin", isin("12")), 200);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT holding_id, trade_date, intraday_qty, intraday_buy_value "
                + "FROM trade_settlement_classifications WHERE broker_account_id = ? ORDER BY trade_date", one.toString());
        assertEquals(3, rows.size(), "the shared day is one row now");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM trade_settlement_classifications WHERE instrument_id = ?",
                Integer.class, s.toString()));
        assertEquals(tHolding, String.valueOf(rows.get(0).get("holding_id")));
        assertEquals(0, new BigDecimal("5").compareTo(new BigDecimal(rows.get(0).get("intraday_qty").toString())));
        assertEquals(0, new BigDecimal("20").compareTo(new BigDecimal(rows.get(0).get("intraday_buy_value").toString())));
        assertEquals(tHolding, String.valueOf(rows.get(1).get("holding_id")));
        assertNull(rows.get(2).get("holding_id"), "a classification without a holding stays without one");
    }

    @Test
    void onlyTheChangedIdentifierPicksTheTargetAndClearingIsRejected() throws Exception {
        // Was identifiersLeadingBackToTheSameInstrumentOrClearedAreRejected (forced rewrite): the target
        // is now looked up by the changed identifiers only, so changing just the Yahoo symbol (the ISIN
        // still being the source's) moves Alice to a row with that symbol instead of bouncing as "the
        // same instrument".
        UUID s = stock("Shared Feed", "SHF", isin("07"), "SHF" + tag + ".NS");
        trade(alice, broker(alice, "A"), s, "buy", "1", "1", today.minusDays(5));
        trade(bob, broker(bob, "B"), s, "buy", "1", "1", today.minusDays(5));

        alice.putJson("/api/v1/instruments/" + s, Map.of("type", "stock", "name", "x"), 400);
        JsonNode cleared = edit(alice, s, new HashMap<>(Map.of("yahooSymbol", "")), 400);
        assertTrue(cleared.get("message").asText().contains("Clearing an identifier"), cleared.toString());
        JsonNode unchanged = alice.getJson("/api/v1/instruments/" + s, 200);
        assertEquals("SHF" + tag + ".NS", unchanged.get("yahooSymbol").asText());
        assertFalse(unchanged.get("overridden").asBoolean(), "a rejected edit stores nothing");
        assertEquals(1, positions(alice, s).size());

        JsonNode moved = edit(alice, s, Map.of("yahooSymbol", "OTHER" + tag + ".NS"), 200);
        UUID t = UUID.fromString(moved.get("id").asText());
        instrumentIds.add(t);
        assertNotEquals(s, t);
        assertEquals("OTHER" + tag + ".NS", moved.get("yahooSymbol").asText());
        assertTrue(moved.get("isin").isNull(), "the ISIN stays the source's (unique in the catalog)");
        assertEquals("Shared Feed " + tag, moved.get("name").asText());
        assertEquals(List.of(), texts(moved.get("overriddenFields")));
        assertDecimal("1", position(alice, t).get("quantity"));
        assertTrue(positions(alice, s).isEmpty());
        assertDecimal("1", position(bob, s).get("quantity"), "Bob stays on the shared row");
        assertEquals("SHF" + tag + ".NS", bob.getJson("/api/v1/instruments/" + s, 200).get("yahooSymbol").asText());
    }

    // ------------------------------------------------------------------ manual prices

    @Test
    void manualPricesAreTheEntrantsOwnAndWinOnTheirDate() throws Exception {
        UUID s = stock("Price Co", "PRC", isin("08"), null);
        trade(alice, broker(alice, "A"), s, "buy", "10", "50", today.minusDays(30));
        trade(bob, broker(bob, "B"), s, "buy", "10", "50", today.minusDays(30));
        feedPrice(s, today.minusDays(3), "80");
        feedPrice(s, today.minusDays(1), "100");

        JsonNode afterPost = alice.postJson("/api/v1/instruments/" + s + "/price",
                Map.of("price", 150, "asOf", today.minusDays(1).toString()), 200);
        assertDecimal("150", afterPost.get("lastPrice"));
        alice.postJson("/api/v1/instruments/" + s + "/price", Map.of("price", 90, "asOf", today.minusDays(3).toString()), 200);

        JsonNode aliceView = alice.getJson("/api/v1/instruments/" + s, 200);
        assertDecimal("150", aliceView.get("lastPrice"));
        assertEquals("MANUAL", aliceView.get("lastPriceSource").asText());
        JsonNode bobView = bob.getJson("/api/v1/instruments/" + s, 200);
        assertDecimal("100", bobView.get("lastPrice"));
        assertEquals("YAHOO", bobView.get("lastPriceSource").asText());

        JsonNode alicePos = position(alice, s);
        assertDecimal("1500", alicePos.get("currentValue"));
        assertDecimal("90", alicePos.get("previousClose"), "her own close of the earlier date too");
        JsonNode bobPos = position(bob, s);
        assertDecimal("1000", bobPos.get("currentValue"));
        assertDecimal("80", bobPos.get("previousClose"));

        // The batch reads (tax harvest's lot engine, the portfolio-value history) take her prices too.
        assertDecimal("150", harvestLot(alice, s).get("price"));
        assertDecimal("100", harvestLot(bob, s).get("price"));
        assertEquals("1500.00", lastPortfolioValue(alice));
        assertEquals("1000.00", lastPortfolioValue(bob));

        JsonNode aliceHistory = alice.getJson("/api/v1/instruments/" + s + "/prices", 200);
        assertEquals(2, aliceHistory.size(), "one row per date: hers in place of the feed's");
        assertEquals("MANUAL", aliceHistory.get(0).get("source").asText());
        assertTrue(aliceHistory.get(0).get("editable").asBoolean());
        JsonNode bobHistory = bob.getJson("/api/v1/instruments/" + s + "/prices", 200);
        assertEquals(2, bobHistory.size());
        assertEquals("YAHOO", bobHistory.get(0).get("source").asText());
        assertFalse(bobHistory.get(0).get("editable").asBoolean());

        String mine = aliceHistory.get(0).get("id").asText();
        String feed = bobHistory.get(0).get("id").asText();
        bob.putJson("/api/v1/instruments/" + s + "/prices/" + mine, Map.of("price", 1), 404);
        bob.deleteJson("/api/v1/instruments/" + s + "/prices/" + mine, 404);
        alice.putJson("/api/v1/instruments/" + s + "/prices/" + feed, Map.of("price", 1), 404);
        alice.deleteJson("/api/v1/instruments/" + s + "/prices/" + feed, 404);
        alice.putJson("/api/v1/instruments/" + UUID.randomUUID() + "/prices/" + mine, Map.of("price", 1), 404);

        assertDecimal("155", alice.putJson("/api/v1/instruments/" + s + "/prices/" + mine, Map.of("price", 155), 200)
                .get("lastPrice"));
        assertDecimal("100", bob.getJson("/api/v1/instruments/" + s, 200).get("lastPrice"));
        alice.deleteJson("/api/v1/instruments/" + s + "/prices/" + mine, 204);
        assertDecimal("100", alice.getJson("/api/v1/instruments/" + s, 200).get("lastPrice"), "the feed price again");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM instrument_prices WHERE instrument_id = ? "
                + "AND as_of = ? AND user_id IS NULL", Integer.class, s.toString(), Date.valueOf(today.minusDays(1))),
                "the feed row was never touched");

        alice.postJson("/api/v1/instruments/" + s + "/price", Map.of("price", -1), 400);
    }

    @Test
    void aLegacyOwnerlessManualPriceIsReadOnlyForEveryone() throws Exception {
        UUID s = stock("Legacy Co", "LGC", isin("09"), null);
        String legacy = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO instrument_prices (id, instrument_id, as_of, close, source, created_at) "
                + "VALUES (?, ?, ?, 42, 'MANUAL', CURRENT_TIMESTAMP)", legacy, s.toString(), Date.valueOf(today.minusDays(2)));

        for (ApiTestClient client : List.of(alice, bob)) {
            JsonNode history = client.getJson("/api/v1/instruments/" + s + "/prices", 200);
            assertEquals(1, history.size());
            assertEquals("MANUAL", history.get(0).get("source").asText());
            assertFalse(history.get(0).get("editable").asBoolean());
            assertDecimal("42", client.getJson("/api/v1/instruments/" + s, 200).get("lastPrice"));
            client.putJson("/api/v1/instruments/" + s + "/prices/" + legacy, Map.of("price", 1), 404);
            client.deleteJson("/api/v1/instruments/" + s + "/prices/" + legacy, 404);
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM instrument_prices WHERE id = ?", Integer.class, legacy));
    }

    @Test
    void theCatalogPickNeverRewritesAnExistingRow() throws Exception {
        UUID s = stock("Picked Co", "PCK", isin("10"), "PCK" + tag + ".NS");
        JsonNode resolved = bob.postJson("/api/v1/instruments/resolve", Map.of("type", "stock", "name", "Hijacked",
                "symbol", "HIJ", "exchange", "BSE", "isin", isin("10"), "yahooSymbol", "HIJ.NS"), 200);
        assertEquals(s.toString(), resolved.get("id").asText());
        JsonNode catalog = alice.getJson("/api/v1/instruments/" + s, 200);
        assertEquals("Picked Co " + tag, catalog.get("name").asText());
        assertEquals("PCK" + tag + ".NS", catalog.get("yahooSymbol").asText());
        assertEquals("NSE", catalog.get("exchange").asText());
    }

    // ------------------------------------------------------------------ fix round: repoint semantics

    @Test
    void anIdentifierEditOntoAnotherInstrumentKeepsItsOwnDisplayValues() throws Exception {
        // Reviewer probe (merge carried the source's display fields onto the target as overrides).
        UUID s = stock("Probe From", "PFR", isin("21"), null);
        UUID t = stock("Probe Into", "PIN", isin("22"), null);
        UUID one = broker(alice, "One");
        trade(alice, one, s, "buy", "10", "100", today.minusDays(50));
        trade(alice, one, t, "buy", "5", "200", today.minusDays(40));
        edit(alice, s, Map.of("name", "My From", "type", "etf"), 200);

        JsonNode moved = edit(alice, s, Map.of("isin", isin("22")), 200);

        assertEquals(t.toString(), moved.get("id").asText());
        assertEquals("Probe Into " + tag, moved.get("name").asText(), "T's own name, not her name for S");
        assertEquals("PIN" + tag.substring(0, 4), moved.get("symbol").asText());
        assertEquals("stock", moved.get("type").asText(), "her type for S is not pinned on T");
        assertEquals(List.of(), texts(moved.get("overriddenFields")));
        assertTrue(moved.get("mergedHoldings").asBoolean(), "the holding at broker One was merged");
        assertTrue(moved.get("mergeNote").isNull(), "neither holding had sells");
        assertEquals("Probe Into " + tag, bob.getJson("/api/v1/instruments/" + t, 200).get("name").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_instrument_overrides WHERE user_id = ?",
                Integer.class, aliceId.toString()), "her overrides on S went with S, none landed on T");
    }

    @Test
    void aDisplayFieldChangedInTheSameEditIsTheOnlyOverrideOnTheTarget() throws Exception {
        UUID s = stock("Change From", "CFR", isin("25"), null);
        UUID t = stock("Change Into", "CIN", isin("26"), null);
        trade(alice, broker(alice, "One"), s, "buy", "1", "100", today.minusDays(10));

        JsonNode moved = edit(alice, s, Map.of("isin", isin("26"), "name", "Mine Now"), 200);

        assertEquals(t.toString(), moved.get("id").asText());
        assertEquals("Mine Now", moved.get("name").asText());
        assertEquals("CIN" + tag.substring(0, 4), moved.get("symbol").asText());
        assertEquals(List.of("name"), texts(moved.get("overriddenFields")));
        assertFalse(moved.get("mergedHoldings").asBoolean(), "a moved holding is not a merge");
        assertEquals("Change Into " + tag, bob.getJson("/api/v1/instruments/" + t, 200).get("name").asText());
    }

    @Test
    void mergingTwoHoldingsThatBothSoldSaysRealisedGainsMayChange() throws Exception {
        UUID s = stock("Sold From", "SFR", isin("27"), null);
        UUID t = stock("Sold Into", "SIN", isin("28"), null);
        UUID one = broker(alice, "One");
        trade(alice, one, s, "buy", "10", "100", today.minusDays(90));
        trade(alice, one, s, "sell", "4", "150", today.minusDays(60));
        trade(alice, one, t, "buy", "10", "200", today.minusDays(80));
        trade(alice, one, t, "sell", "2", "210", today.minusDays(30));

        JsonNode moved = edit(alice, s, Map.of("isin", isin("28")), 200);

        assertTrue(moved.get("mergedHoldings").asBoolean());
        assertTrue(moved.get("mergeNote").asText().contains("realised gains"), moved.toString());
        assertFalse(alice.getJson("/api/v1/instruments/" + t, 200).get("mergedHoldings").asBoolean(),
                "only the edit's answer carries the merge outcome");
    }

    @Test
    void changingOnlyTheIsinFindsTheRowWithThatIsinOrAddsOneWithJustIt() throws Exception {
        // Reviewer probe (a changed ISIN bounced because the unchanged Yahoo symbol found the source).
        UUID s = stock("Partial From", "PPA", isin("23"), "PPA" + tag + ".NS");
        UUID t = stock("Partial Into", "PPI", isin("24"), null);
        trade(alice, broker(alice, "A"), s, "buy", "1", "1", today.minusDays(5));

        JsonNode onto = edit(alice, s, Map.of("isin", isin("24")), 200);
        assertEquals(t.toString(), onto.get("id").asText());

        UUID s2 = stock("Partial Two", "PPT", isin("29"), "PPT" + tag + ".NS");
        trade(alice, broker(alice, "B"), s2, "buy", "1", "1", today.minusDays(5));
        JsonNode added = edit(alice, s2, Map.of("isin", isin("30")), 200);
        UUID n = UUID.fromString(added.get("id").asText());
        instrumentIds.add(n);
        assertNotEquals(s2, n);
        assertEquals(isin("30"), added.get("isin").asText());
        assertTrue(added.get("yahooSymbol").isNull(), "the Yahoo symbol stays the source's: no duplicate feed row");
        assertEquals("PPT" + tag + ".NS", jdbc.queryForObject("SELECT yahoo_symbol FROM instruments WHERE id = ?",
                String.class, s2.toString()));
    }

    @Test
    void anIdentifierEditIsRefusedWhenEitherInstrumentTakesPartInACorporateAction() throws Exception {
        // Reviewer probe (a repoint across a demerger detached the child's seeded shares).
        UUID parent = stock("Demerge Parent", "DMP", isin("31"), null);
        UUID child = stock("Demerge Child", "DMC", isin("32"), null);
        UUID one = broker(alice, "One");
        trade(alice, one, parent, "buy", "10", "100", today.minusDays(100));
        alice.create("/api/v1/instruments/" + parent + "/corporate-actions", Map.of("type", "demerger", "ratioFrom", 1,
                "ratioTo", 1, "exDate", today.minusDays(50).toString(), "targetInstrumentId", child.toString(),
                "costAllocationPct", 30));
        JsonNode childBefore = position(alice, child);

        JsonNode refused = edit(alice, parent, Map.of("isin", isin("33")), 400);
        assertTrue(refused.get("message").asText().contains("demerger of Demerge Parent " + tag), refused.toString());
        JsonNode childRefused = edit(alice, child, Map.of("isin", isin("33")), 400);
        assertTrue(childRefused.get("message").asText().contains("corporate action"), childRefused.toString());
        assertEquals(1, positions(alice, parent).size(), "nothing moved");
        assertDecimal(childBefore.get("quantity").decimalValue().toPlainString(), position(alice, child).get("quantity"));

        // A corporate action on the TARGET refuses too (its split would apply to the moved lots).
        UUID plain = stock("Plain From", "PLF", isin("34"), null);
        UUID splitting = stock("Splitting Into", "SPI", isin("35"), null);
        trade(alice, one, plain, "buy", "3", "100", today.minusDays(20));
        alice.create("/api/v1/instruments/" + splitting + "/corporate-actions", Map.of("type", "split", "ratioFrom", 1,
                "ratioTo", 2, "exDate", today.plusDays(30).toString()));
        JsonNode targetRefused = edit(alice, plain, Map.of("isin", isin("35")), 400);
        assertTrue(targetRefused.get("message").asText().contains("split of Splitting Into " + tag), targetRefused.toString());
        assertDecimal("3", position(alice, plain).get("quantity"));
    }

    @Test
    void aTickerTakenInAnotherCaseStaysTheUsersOwnOnTheNewRow() throws Exception {
        UUID taken = stock("Ticker Owner", "TKO", isin("36"), null);
        String takenSymbol = "TKO" + tag.substring(0, 4);
        UUID s = stock("Ticker From", "TKF", isin("37"), null);
        trade(alice, broker(alice, "A"), s, "buy", "1", "1", today.minusDays(5));

        JsonNode moved = edit(alice, s, Map.of("isin", isin("38"), "symbol", takenSymbol.toLowerCase(Locale.ROOT)), 200);
        UUID n = UUID.fromString(moved.get("id").asText());
        instrumentIds.add(n);

        assertNotEquals(taken, n);
        assertNull(jdbc.queryForObject("SELECT symbol FROM instruments WHERE id = ?", String.class, n.toString()),
                "the ticker belongs to another row whatever its case");
        assertEquals(takenSymbol.toLowerCase(Locale.ROOT), moved.get("symbol").asText());
        assertTrue(texts(moved.get("overriddenFields")).contains("symbol"));
    }

    // ------------------------------------------------------------------ fix round: catalog minting

    @Test
    void creatingAnInstrumentWithAKnownIdentifierReturnsTheExistingRow() throws Exception {
        UUID s = stock("Known Co", "KNW", isin("51"), "KNW" + tag + ".NS");
        Map<String, Object> sameYahoo = new HashMap<>(Map.of("type", "stock", "name", "Dup Co",
                "yahooSymbol", ("KNW" + tag + ".NS").toLowerCase(Locale.ROOT)));
        assertEquals(s.toString(), alice.postJson("/api/v1/instruments", sameYahoo, 201).get("id").asText());
        Map<String, Object> sameIsin = new HashMap<>(Map.of("type", "stock", "name", "Dup Co", "isin", isin("51")));
        assertEquals(s.toString(), bob.postJson("/api/v1/instruments", sameIsin, 201).get("id").asText());

        String amfi = "9" + Math.abs(tag.hashCode() % 100000);
        Map<String, Object> fund = new HashMap<>(Map.of("type", "mutual_fund", "name", "Fund " + tag, "amfiCode", amfi));
        UUID f = UUID.fromString(alice.postJson("/api/v1/instruments", fund, 201).get("id").asText());
        instrumentIds.add(f);
        assertEquals(f.toString(), bob.postJson("/api/v1/instruments",
                new HashMap<>(Map.of("type", "mutual_fund", "name", "Other", "amfiCode", amfi)), 201).get("id").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM instruments WHERE amfi_code = ?", Integer.class, amfi));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM instruments WHERE UPPER(yahoo_symbol) = ?",
                Integer.class, ("KNW" + tag + ".NS").toUpperCase(Locale.ROOT)));
    }

    @Test
    void duplicateFeedIdentifiersAlreadyInTheCatalogResolveToTheOldestRow() throws Exception {
        String yahoo = "DUP" + tag + ".NS";
        UUID oldest = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        instrumentIds.add(oldest);
        instrumentIds.add(newer);
        jdbc.update("INSERT INTO instruments (id, type, name, yahoo_symbol, currency, created_at, updated_at) "
                + "VALUES (?, 'stock', ?, ?, 'INR', ?, ?)", oldest.toString(), "Dup Old " + tag, yahoo,
                java.sql.Timestamp.from(java.time.Instant.parse("2024-01-01T00:00:00Z")),
                java.sql.Timestamp.from(java.time.Instant.parse("2024-01-01T00:00:00Z")));
        jdbc.update("INSERT INTO instruments (id, type, name, yahoo_symbol, currency, created_at, updated_at) "
                + "VALUES (?, 'stock', ?, ?, 'INR', ?, ?)", newer.toString(), "Dup New " + tag, yahoo.toLowerCase(Locale.ROOT),
                java.sql.Timestamp.from(java.time.Instant.parse("2025-01-01T00:00:00Z")),
                java.sql.Timestamp.from(java.time.Instant.parse("2025-01-01T00:00:00Z")));
        UUID s = stock("Dup Source", "DSR", isin("52"), null);
        trade(alice, broker(alice, "A"), s, "buy", "1", "1", today.minusDays(5));

        assertEquals(oldest.toString(), alice.postJson("/api/v1/instruments",
                new HashMap<>(Map.of("type", "stock", "name", "Again", "yahooSymbol", yahoo)), 201).get("id").asText());
        JsonNode moved = edit(alice, s, Map.of("yahooSymbol", yahoo.toLowerCase(Locale.ROOT)), 200);
        assertEquals(oldest.toString(), moved.get("id").asText());
    }

    // ------------------------------------------------------------------ fix round: imports after a repoint

    private static final String TRADEBOOK_HEADER = "symbol,isin,trade_date,exchange,segment,series,trade_type,auction,"
            + "quantity,price,trade_id,order_id,order_execution_time\n";

    private String tradebook(String symbol, String isin, LocalDate day, String tradeId) {
        return TRADEBOOK_HEADER + symbol + "," + isin + "," + day + ",NSE,EQ,EQ,buy,false,10,100.0," + tradeId + ","
                + tradeId + "9," + day + " 10:00:00\n";
    }

    private ImportPreviewResponse.ImportRowPreviewDto preview(UUID user, UUID broker, String csv) {
        UserContext.setCurrentUserId(user);
        try {
            return importService.preview(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                    ImportSource.zerodha_tradebook, broker).rows().get(0);
        } finally {
            UserContext.clear();
        }
    }

    private ImportCommitResponse commit(UUID user, UUID broker, ImportPreviewResponse.ImportRowPreviewDto row,
                                        UUID instrumentId, ImportCommitRequest.CreateInstrumentDto newInstrument) {
        var parsed = row.parsedRow();
        ImportCommitRequest.CommitRowDto dto = new ImportCommitRequest.CommitRowDto(row.rowIndex(), false, instrumentId,
                newInstrument, new ImportCommitRequest.ParsedRowData(parsed.kind(), parsed.type(), parsed.quantity(),
                parsed.price(), parsed.amount(), parsed.tradeDate(), parsed.charges(), parsed.externalRef(), null));
        UserContext.setCurrentUserId(user);
        try {
            return importService.commit(ImportSource.zerodha_tradebook, broker, List.of(dto));
        } finally {
            UserContext.clear();
        }
    }

    private List<String> repointRows(UUID user) {
        List<String> out = new ArrayList<>();
        jdbc.queryForList("SELECT from_instrument_id, to_instrument_id FROM user_instrument_repoints WHERE user_id = ? "
                + "ORDER BY from_instrument_id", user.toString())
                .forEach(r -> out.add(r.get("from_instrument_id") + "->" + r.get("to_instrument_id")));
        return out;
    }

    @Test
    void aReimportAfterARepointLandsOnTheTargetAndIsADuplicateThere() throws Exception {
        UUID s = stock("Import From", "IMF", isin("41"), null);
        String symbol = "IMF" + tag.substring(0, 4);
        UUID aliceBroker = broker(alice, "Alice Broker");
        UUID bobBroker = broker(bob, "Bob Broker");
        String csv = tradebook(symbol, isin("41"), today.minusDays(30), "5" + tag.hashCode() % 1000);

        var first = preview(aliceId, aliceBroker, csv);
        assertEquals(s, first.matchedInstrument().id());
        assertEquals(1, commit(aliceId, aliceBroker, first, s, null).committed());
        var bobFirst = preview(bobId, bobBroker, csv);
        assertEquals(1, commit(bobId, bobBroker, bobFirst, s, null).committed());

        UUID t = UUID.fromString(edit(alice, s, Map.of("isin", isin("42")), 200).get("id").asText());
        instrumentIds.add(t);
        assertEquals(List.of(s + "->" + t), repointRows(aliceId));

        var again = preview(aliceId, aliceBroker, csv);
        assertEquals(t, again.matchedInstrument().id(), "the ISIN match stands for the instrument she moved to");
        assertTrue(again.duplicate(), "and the duplicate check looks at her holding of it");
        ImportCommitResponse stale = commit(aliceId, aliceBroker, again, s, null);
        assertEquals(0, stale.committed(), "a row still naming the old instrument lands on the target as a duplicate");
        assertEquals(1, stale.skipped());
        ImportCommitResponse byIsin = commit(aliceId, aliceBroker, again, null,
                new ImportCommitRequest.CreateInstrumentDto(null, null, symbol, "NSE", isin("41"), null, null));
        assertEquals(0, byIsin.committed());
        assertEquals(1, positions(alice, t).size());
        assertDecimal("10", position(alice, t).get("quantity"));
        assertTrue(positions(alice, s).isEmpty());

        var bobAgain = preview(bobId, bobBroker, csv);
        assertEquals(s, bobAgain.matchedInstrument().id(), "Bob never moved");
        assertTrue(bobAgain.duplicate());

        // A chain S -> T -> U is kept flat and resolves in one step.
        UUID u = UUID.fromString(edit(alice, t, Map.of("isin", isin("43")), 200).get("id").asText());
        instrumentIds.add(u);
        List<String> chained = repointRows(aliceId);
        assertEquals(2, chained.size());
        assertTrue(chained.contains(s + "->" + u) && chained.contains(t + "->" + u), chained.toString());
        var chainedPreview = preview(aliceId, aliceBroker, csv);
        assertEquals(u, chainedPreview.matchedInstrument().id());
        assertTrue(chainedPreview.duplicate());

        // Moving back onto S removes the record that sent S elsewhere (and adds none for U).
        JsonNode back = edit(alice, u, Map.of("isin", isin("41")), 200);
        assertEquals(s.toString(), back.get("id").asText());
        assertEquals(List.of(t + "->" + s), repointRows(aliceId));
        var reverted = preview(aliceId, aliceBroker, csv);
        assertEquals(s, reverted.matchedInstrument().id());
        assertTrue(reverted.duplicate());
        assertDecimal("10", position(alice, s).get("quantity"));
    }

    // ------------------------------------------------------------------ fix round: legacy shared prices

    @Test
    void aFutureDatedLegacyManualPriceIsNotTheLatestPrice() throws Exception {
        UUID s = stock("Future Co", "FUT", isin("53"), null);
        trade(alice, broker(alice, "A"), s, "buy", "10", "50", today.minusDays(30));
        feedPrice(s, today.minusDays(1), "100");
        jdbc.update("INSERT INTO instrument_prices (id, instrument_id, as_of, close, source, created_at) "
                + "VALUES (?, ?, ?, 999, 'MANUAL', CURRENT_TIMESTAMP)", UUID.randomUUID().toString(), s.toString(),
                Date.valueOf(today.plusDays(5)));

        for (ApiTestClient client : List.of(alice, bob)) {
            assertDecimal("100", client.getJson("/api/v1/instruments/" + s, 200).get("lastPrice"));
        }
        assertDecimal("1000", position(alice, s).get("currentValue"));

        alice.postJson("/api/v1/instruments/" + s + "/price", Map.of("price", 120, "asOf", today.toString()), 200);
        assertDecimal("120", alice.getJson("/api/v1/instruments/" + s, 200).get("lastPrice"), "her own price counts");
    }

    // ------------------------------------------------------------------ fix round: instrument list

    @Test
    void theInstrumentListPagesAndFiltersOnTheTypeEachUserSees() throws Exception {
        UUID a = stock("Paged A", "PGA", isin("61"), null);
        UUID b = stock("Paged B", "PGB", isin("62"), null);
        UUID c = stock("Paged C", "PGC", isin("63"), null);
        edit(alice, b, Map.of("type", "etf"), 200);

        JsonNode firstPage = alice.getJson("/api/v1/instruments?search=Paged&size=1&page=0", 200);
        assertEquals(1, firstPage.size(), "an empty-ish search pages, it does not load the catalog");
        JsonNode secondPage = alice.getJson("/api/v1/instruments?search=Paged&size=1&page=1", 200);
        assertEquals(1, secondPage.size());
        assertNotEquals(firstPage.get(0).get("id").asText(), secondPage.get(0).get("id").asText());

        List<String> aliceStocks = new ArrayList<>();
        alice.getJson("/api/v1/instruments?search=" + tag + "&type=stock", 200).forEach(n -> aliceStocks.add(n.get("id").asText()));
        assertTrue(aliceStocks.containsAll(List.of(a.toString(), c.toString())));
        assertFalse(aliceStocks.contains(b.toString()), "she retyped B");
        List<String> aliceEtfs = new ArrayList<>();
        alice.getJson("/api/v1/instruments?search=" + tag + "&type=etf", 200).forEach(n -> aliceEtfs.add(n.get("id").asText()));
        assertEquals(List.of(b.toString()), aliceEtfs);
        List<String> bobStocks = new ArrayList<>();
        bob.getJson("/api/v1/instruments?search=" + tag + "&type=stock", 200).forEach(n -> bobStocks.add(n.get("id").asText()));
        assertTrue(bobStocks.containsAll(List.of(a.toString(), b.toString(), c.toString())));
        assertEquals(1, alice.getJson("/api/v1/instruments?size=1", 200).size(), "a blank search is paged too");
    }
}
