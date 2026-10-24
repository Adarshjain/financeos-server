package com.financeos.api.report;

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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Computed datasources run without the caller's own transaction; they must still be tenant-scoped:
 * user B running portfolio_value / positions as ad hoc reports, saved reports on a dashboard and KPI
 * underlying never sees user A's holdings.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComputedDatasourceTenancyIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient alice;
    private ApiTestClient bob;
    private UUID aliceId;
    private UUID bobId;
    private String aliceBrokerName;
    private String aliceInstrumentName;

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String aliceEmail = "cds-a-" + tag + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, aliceEmail);
        aliceId = userRepository.findByEmail(aliceEmail).orElseThrow().getId();
        String bobEmail = "cds-b-" + tag + "@example.test";
        bob = ApiTestClient.signUp(mockMvc, mapper, bobEmail);
        bobId = userRepository.findByEmail(bobEmail).orElseThrow().getId();

        aliceBrokerName = "AliceBroker" + tag;
        aliceInstrumentName = "AliceStock" + tag;
        UUID broker = alice.create("/api/v1/accounts", Map.of("type", "broker", "name", aliceBrokerName,
                "provider", "Zerodha", "clientId", "CDS1", "cashBalance", 0, "financialPosition", "asset",
                "excludeFromNetAsset", false));
        Map<String, Object> instrument = new HashMap<>();
        instrument.put("type", "stock");
        instrument.put("name", aliceInstrumentName);
        instrument.put("symbol", "CDS" + tag.substring(0, 4));
        instrument.put("exchange", "NSE");
        instrument.put("isin", "INE" + tag + "01");
        UUID instrumentId = alice.create("/api/v1/instruments", instrument);
        alice.create("/api/v1/investments/transactions", Map.of("brokerAccountId", broker.toString(),
                "instrumentId", instrumentId.toString(), "type", "buy", "quantity", "10", "price", "100",
                "tradeDate", AppTime.today().minusDays(30).toString()));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId, bobId));
    }

    @Test
    void aliceSeesHerOwnPortfolioRows() throws Exception {
        for (String datasource : List.of("portfolio_value", "positions")) {
            assertTrue(adHocTable(alice, datasource).contains(aliceInstrumentName), datasource);
        }
    }

    @Test
    void bobNeverSeesAlicesHoldings_adHocAndSavedOnADashboard() throws Exception {
        for (String datasource : List.of("portfolio_value", "positions")) {
            String adHoc = adHocTable(bob, datasource);
            assertFalse(adHoc.contains(aliceInstrumentName) || adHoc.contains(aliceBrokerName), datasource + adHoc);

            UUID id = bob.create("/api/v1/reports", Map.of("name", "Bob " + datasource, "type", "TABLE",
                    "datasource", datasource, "definition", rawTable()));
            Map<String, Object> widget = new LinkedHashMap<>();
            widget.put("id", UUID.randomUUID().toString());
            widget.put("reportId", id.toString());
            widget.put("layout", Map.of("x", 0, "y", 0, "w", 6, "h", 4));
            bob.create("/api/v1/dashboards", Map.of("name", "Bob board " + datasource, "widgets", List.of(widget)));
            String saved = bob.postJson("/api/v1/reports/" + id + "/data", null, 200).toString();
            assertFalse(saved.contains(aliceInstrumentName) || saved.contains(aliceBrokerName), datasource + saved);
            assertEquals(0, bob.postJson("/api/v1/reports/" + id + "/data", null, 200).get("rows").size());
        }
    }

    @Test
    void bobsKpiUnderlyingOnPositionsListsNoneOfAlicesRows() throws Exception {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("measure", "currentValue");
        kpi.put("aggregation", "sum");
        kpi.put("filters", List.of());
        String body = bob.postJson("/api/v1/reports/underlying", Map.of("type", "KPI",
                "datasource", "positions", "definition", kpi), 200).toString();

        assertFalse(body.contains(aliceInstrumentName) || body.contains(aliceBrokerName), body);
        assertEquals(0, mapper.readTree(body).get("rowCount").asLong(), body);
    }

    @Test
    void bobsBuiltinAllocationWidgetHasNoneOfAlicesRows() throws Exception {
        String body = bob.postJson("/api/v1/dashboards/builtins/allocation/data", null, 200).toString();

        assertFalse(body.contains(aliceInstrumentName) || body.contains(aliceBrokerName), body);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> rawTable() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("mode", "raw");
        raw.put("columns", List.of("broker", "instrument"));
        raw.put("filters", List.of());
        raw.put("sort", List.of());
        return raw;
    }

    private static String adHocTable(ApiTestClient client, String datasource) throws Exception {
        JsonNode table = client.postJson("/api/v1/reports/data", Map.of("type", "TABLE",
                "datasource", datasource, "definition", rawTable()), 200);
        return table.get("rows").toString();
    }
}
