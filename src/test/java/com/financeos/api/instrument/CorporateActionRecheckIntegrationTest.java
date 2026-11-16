package com.financeos.api.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

/** Changing a corporate action rechecks the user's suggestions at once, without waiting for the nightly detection. */
@SpringBootTest
@AutoConfigureMockMvc
class CorporateActionRecheckIntegrationTest {

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

    private List<Map<String, Object>> rows(UUID user, UUID instrument, String kind) {
        return jdbc.queryForList("SELECT status, corporate_action_id, resolved_at FROM corporate_action_suggestions "
                + "WHERE user_id = ? AND instrument_id = ? AND kind = ?", user.toString(), instrument.toString(), kind);
    }

    private UUID split(UUID instrument, LocalDate ex) throws Exception {
        return alice.create("/api/v1/instruments/" + instrument + "/corporate-actions", Map.of("type", "split", "ratioFrom", 1,
                "ratioTo", 2, "exDate", ex.toString()));
    }

    @Test
    void recordingASplitByHandClosesTheMatchingPendingSuggestion() throws Exception {
        UUID s = stock("Done Co", "DNC", "INE" + tag + "01");
        trade(alice, broker(alice, "A"), s, "buy", "10", "100", today.minusDays(100));
        LocalDate ex = today.minusDays(30);
        suggestion(aliceId, s, "split", ex, 1, 2, "pending", "YAHOO_MISMATCH", null);

        UUID action = split(s, ex);

        Map<String, Object> row = rows(aliceId, s, "split").get(0);
        assertEquals("accepted", row.get("STATUS"));
        assertEquals(action.toString(), String.valueOf(row.get("CORPORATE_ACTION_ID")));
        assertNotNull(row.get("RESOLVED_AT"));
    }

    @Test
    void aDifferentExDateOrKindLeavesThePendingSuggestionOpen() throws Exception {
        UUID s = stock("Other Co", "OTC", "INE" + tag + "02");
        trade(alice, broker(alice, "A"), s, "buy", "10", "100", today.minusDays(100));
        suggestion(aliceId, s, "split", today.minusDays(30), 1, 2, "pending", "YAHOO_MISMATCH", null);
        suggestion(aliceId, s, "bonus", today.minusDays(40), 1, 2, "pending", "YAHOO_MISMATCH", null);

        split(s, today.minusDays(31));
        split(s, today.minusDays(40));

        assertEquals("pending", rows(aliceId, s, "split").get(0).get("STATUS"));
        assertEquals("pending", rows(aliceId, s, "bonus").get(0).get("STATUS"));
    }

    @Test
    void deletingTheActionThatCuredAnOversoldReopensItAndRecordingAFixRemovesIt() throws Exception {
        UUID s = stock("Short Co", "SHC", "INE" + tag + "03");
        UUID br = broker(alice, "A");
        trade(alice, br, s, "buy", "10", "100", today.minusDays(100));
        UUID action = split(s, today.minusDays(50));
        trade(alice, br, s, "sell", "20", "60", today.minusDays(10));
        assertEquals(0, rows(aliceId, s, "oversold").size(), "the split covers the sell");

        alice.deleteJson("/api/v1/instruments/" + s + "/corporate-actions/" + action, 204);
        assertEquals(1, rows(aliceId, s, "oversold").size(), "without the split the sell is oversold");
        assertEquals("pending", rows(aliceId, s, "oversold").get(0).get("STATUS"));

        split(s, today.minusDays(50));
        assertEquals(0, rows(aliceId, s, "oversold").size(), "recording the fix clears it");
    }
}
