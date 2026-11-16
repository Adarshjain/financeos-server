package com.financeos.domain.instrument.price;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** The monthly feed-price prune of untraded instruments spares demerger/merger targets (they have no trades). */
@SpringBootTest
@AutoConfigureMockMvc
class PriceCleanupMergerTargetIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private com.financeos.domain.instrument.InstrumentPriceRepository priceRepository;

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

    private void prices(UUID instrument) {
        for (int d = 1; d <= 5; d++) {
            jdbc.update("INSERT INTO instrument_prices (id, instrument_id, as_of, close, source, created_at) VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), instrument.toString(), java.sql.Date.valueOf(today.minusDays(d)), BigDecimal.TEN,
                    "YAHOO", Timestamp.from(Instant.now()));
        }
    }

    private int count(UUID instrument) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM instrument_prices WHERE instrument_id = ?", Integer.class, instrument.toString());
    }

    @Test
    void aMergerTargetsHistorySurvivesButAnUntradedNonTargetIsPruned() throws Exception {
        UUID parent = stock("Parent Co", "PRC", "INE" + tag + "01");
        UUID target = stock("Target Co", "TGC", "INE" + tag + "02");
        UUID loose = stock("Loose Co", "LSC", "INE" + tag + "03");
        alice.create("/api/v1/instruments/" + parent + "/corporate-actions", Map.of("type", "demerger", "ratioFrom", 1, "ratioTo", 1,
                "exDate", today.minusDays(30).toString(), "targetInstrumentId", target.toString(), "costAllocationPct", 20));
        prices(target);
        prices(loose);

        int deleted = priceRepository.deleteFeedPricesOfUntradedInstruments(1000);

        assertEquals(5, count(target), "the target keeps its backfilled history");
        assertEquals(1, count(loose), "an untraded non-target keeps only its newest row");
        assertTrue(deleted >= 4);
    }
}
