package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Net worth's Kind and Side read for people end to end: the catalog, the KPI's underlying data
 * (columns, filter chips, CSV) and raw table, pivot and chart runs carry the value labels, while
 * row values, filters and sorting keep the stored values.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NetWorthValueLabelsIntegrationTest {

    private static final Map<String, String> KIND_LABELS = Map.of("bank_account", "Bank account", "credit_card", "Credit card",
            "broker", "Broker", "generic", "Wallet/Cash", "loan", "Loan", "lending", "Lending");
    private static final Map<String, String> SIDE_LABELS = Map.of("asset", "Asset", "liability", "Liability");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private UUID userId;
    private UUID bank;
    private UUID wallet;

    @BeforeEach
    void setUp() throws Exception {
        String email = "nw-labels-it-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main Bank", "last4", "1234",
                "openingBalance", 5000, "financialPosition", "asset", "excludeFromNetAsset", false));
        wallet = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Pocket cash",
                "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void theCatalogLabelsKindAndSideValues() throws Exception {
        JsonNode catalog = api.getJson("/api/v1/report/datasource", 200);

        JsonNode fields = null;
        for (JsonNode ds : catalog.get("datasources")) {
            if ("net_worth".equals(ds.get("name").asText())) {
                fields = ds.get("fields");
            }
        }
        assertEquals(KIND_LABELS, labels(field(fields, "kind").get("valueLabels")));
        assertEquals(List.of("bank_account", "credit_card", "broker", "generic", "loan", "lending"),
                names(field(fields, "kind").get("valueLabels")));
        assertEquals(SIDE_LABELS, labels(field(fields, "side").get("valueLabels")));
        assertFalse(field(fields, "name").has("valueLabels"));
    }

    @Test
    void underlyingDataColumnsCarryTheLabelsAndRowsKeepTheStoredValues() throws Exception {
        JsonNode body = api.postJson("/api/v1/dashboards/builtins/net_worth/underlying", null, 200);

        JsonNode table = body.get("table");
        assertEquals(KIND_LABELS, labels(column(table, "kind").get("valueLabels")));
        assertEquals(SIDE_LABELS, labels(column(table, "side").get("valueLabels")));
        assertFalse(column(table, "name").has("valueLabels"));
        assertFalse(column(table, "signedValue").has("valueLabels"));
        Map<String, String> kinds = new LinkedHashMap<>();
        table.get("rows").forEach(r -> kinds.put(r.get("id").asText(), r.get("kind").asText()));
        assertEquals("bank_account", kinds.get(bank.toString()));
        assertEquals("generic", kinds.get(wallet.toString()));
    }

    @Test
    void filterChipsAndCsvNameTheValuesByTheirLabelsWhileFiltersMatchStoredValues() throws Exception {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", "signedValue");
        def.put("aggregation", "sum");
        def.put("filters", List.of(
                Map.of("field", "side", "operator", "is", "value", "asset"),
                Map.of("field", "kind", "operator", "in", "value", List.of("generic", "broker"))));
        Map<String, Object> request = Map.of("type", "KPI", "datasource", "net_worth", "definition", def);

        JsonNode body = api.postJson("/api/v1/reports/underlying", request, 200);
        MockHttpServletResponse csv = api.post("/api/v1/reports/underlying/csv", request);

        List<String> chips = new ArrayList<>();
        body.get("filters").forEach(c -> chips.add(c.get("fieldLabel").asText() + " " + c.get("text").asText()));
        assertEquals(List.of("Side is Asset", "Kind in Wallet/Cash, Broker"), chips);
        assertEquals(1, body.get("rowCount").asLong());
        assertEquals(wallet.toString(), body.get("table").get("rows").get(0).get("id").asText());
        String[] lines = csv.getContentAsString(StandardCharsets.UTF_8).split("\r\n");
        assertEquals(2, lines.length);
        assertTrue(lines[1].startsWith("Pocket cash,Wallet/Cash,Asset,"), lines[1]);
    }

    @Test
    void rawTablePivotAndChartRunsCarryTheLabels() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("mode", "raw");
        raw.put("columns", List.of("name", "kind", "side"));
        raw.put("filters", List.of());
        raw.put("sort", List.of(Map.of("key", "kind", "direction", "asc")));
        Map<String, Object> pivot = new LinkedHashMap<>();
        pivot.put("mode", "aggregated");
        pivot.put("rows", List.of(Map.of("field", "kind")));
        pivot.put("columns", List.of(Map.of("field", "side")));
        pivot.put("measures", List.of(Map.of("field", "value", "aggregation", "sum")));
        pivot.put("filters", List.of());
        pivot.put("sort", List.of());
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", "bar");
        chart.put("dimension", Map.of("field", "kind"));
        chart.put("series", Map.of("field", "side"));
        chart.put("measure", Map.of("field", "value", "aggregation", "sum"));
        chart.put("filters", List.of());

        JsonNode table = api.postJson("/api/v1/reports/data", Map.of("type", "TABLE", "datasource", "net_worth",
                "definition", raw), 200);
        JsonNode pivoted = api.postJson("/api/v1/reports/data", Map.of("type", "TABLE", "datasource", "net_worth",
                "definition", pivot), 200);
        JsonNode charted = api.postJson("/api/v1/reports/data", Map.of("type", "CHART", "datasource", "net_worth",
                "definition", chart), 200);

        assertEquals(KIND_LABELS, labels(column(table, "kind").get("valueLabels")));
        assertEquals(List.of("bank_account", "generic"), texts(table.get("rows"), "kind"));
        assertEquals(KIND_LABELS, labels(pivoted.get("rowDimensions").get(0).get("valueLabels")));
        assertEquals(SIDE_LABELS, labels(pivoted.get("columnDimensions").get(0).get("valueLabels")));
        assertEquals(KIND_LABELS, labels(charted.get("valueLabels")));
        assertEquals(SIDE_LABELS, labels(charted.get("seriesValueLabels")));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonNode field(JsonNode fields, String name) {
        for (JsonNode f : fields) {
            if (name.equals(f.get("name").asText())) {
                return f;
            }
        }
        throw new AssertionError("no field " + name);
    }

    private static JsonNode column(JsonNode table, String key) {
        for (JsonNode c : table.get("columns")) {
            if (key.equals(c.get("key").asText())) {
                return c;
            }
        }
        throw new AssertionError("no column " + key);
    }

    private static Map<String, String> labels(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<String> texts(JsonNode rows, String key) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.get(key).asText()));
        return out;
    }
}
