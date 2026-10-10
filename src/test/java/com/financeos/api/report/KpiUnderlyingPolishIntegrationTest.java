package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Over real persistence: a transaction's report description is the text the app shows for it
 * (its own description, else the one it was imported with) in KPI underlying rows, their CSV and
 * sort, description filters, raw tables and pivots; net worth's underlying data carries each side's
 * total over every row; and a money KPI (the transactions amount KPI, built-in net worth) reports
 * the currency format its value renders with.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KpiUnderlyingPolishIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private UUID userId;
    private UUID bankId;
    private LocalDate today;

    /** Imported with only a bank narration (no description of its own). */
    private UUID sourcedOnly;
    /** Has its own description and an imported narration: its own text wins. */
    private UUID both;
    /** Neither. */
    private UUID neither;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String email = "vud-polish-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        bankId = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main Bank",
                "last4", "1234", "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));

        sourcedOnly = txn(bankId, "400", null, "UPI/SWIGGY/ORDER");
        both = txn(bankId, "300", "Groceries", "POS BIGBASKET");
        neither = txn(bankId, "100", null, null);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    // ------------------------------------------------------------------ description fallback

    @Test
    void underlyingRowsShowTheImportedTextWhenThereIsNoOwnDescription() throws Exception {
        JsonNode body = api.postJson("/api/v1/reports/underlying", adHocKpi(List.of()), 200);

        Map<UUID, JsonNode> byId = rowsById(body.get("table"));
        assertEquals("UPI/SWIGGY/ORDER", byId.get(sourcedOnly).get("description").asText());
        assertEquals("Groceries", byId.get(both).get("description").asText());
        assertTrue(byId.get(neither).get("description").isNull());
    }

    @Test
    void underlyingSortByDescriptionOrdersByTheShownText() throws Exception {
        JsonNode asc = api.postJson("/api/v1/reports/underlying?sort=description,asc", adHocKpi(List.of()), 200);
        JsonNode desc = api.postJson("/api/v1/reports/underlying?sort=description,desc", adHocKpi(List.of()), 200);

        List<UUID> ascIds = rowIds(asc.get("table"));
        List<UUID> descIds = rowIds(desc.get("table"));
        // "Groceries" < "UPI/SWIGGY/ORDER" (where the empty one lands is the database's null order).
        assertTrue(ascIds.indexOf(both) < ascIds.indexOf(sourcedOnly), ascIds.toString());
        assertTrue(descIds.indexOf(sourcedOnly) < descIds.indexOf(both), descIds.toString());
    }

    @Test
    void underlyingCsvCarriesTheShownText() throws Exception {
        MockHttpServletResponse csv = api.post("/api/v1/reports/underlying/csv?sort=description,desc",
                adHocKpi(List.of()));

        assertEquals(200, csv.getStatus());
        byte[] bytes = csv.getContentAsByteArray();
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.contains(",UPI/SWIGGY/ORDER,Main Bank,"), text);
        assertTrue(text.contains(",Groceries,Main Bank,"), text);
        assertTrue(!text.contains("POS BIGBASKET"), text);
    }

    @Test
    void descriptionFiltersMatchTheShownText() throws Exception {
        assertListed(filter("description", "contains", "swiggy"), "400", List.of(sourcedOnly));
        assertListed(filter("description", "starts_with", "upi/"), "400", List.of(sourcedOnly));
        assertListed(filter("description", "ends_with", "/order"), "400", List.of(sourcedOnly));
        assertListed(filter("description", "exact", "UPI/SWIGGY/ORDER"), "400", List.of(sourcedOnly));
        assertListed(filter("description", "in", List.of("UPI/SWIGGY/ORDER", "Groceries")), "700",
                List.of(sourcedOnly, both));
        // A transaction with its own description is not found by its imported text.
        assertListed(filter("description", "contains", "bigbasket"), "0", List.of());
    }

    @Test
    void rawTablesAndPivotsShowAndFilterTheShownText() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("mode", "raw");
        raw.put("columns", List.of("date", "description", "spend"));
        raw.put("filters", List.of(filter("description", "contains", "swiggy")));
        raw.put("sort", List.of());
        JsonNode table = api.postJson("/api/v1/reports/data",
                Map.of("type", "TABLE", "datasource", "transactions", "definition", raw), 200);

        assertEquals(1, table.get("rows").size());
        assertEquals("UPI/SWIGGY/ORDER", table.get("rows").get(0).get("description").asText());

        Map<String, Object> pivot = new LinkedHashMap<>();
        pivot.put("mode", "aggregated");
        pivot.put("rows", List.of(Map.of("field", "description")));
        pivot.put("columns", List.of());
        pivot.put("measures", List.of(Map.of("field", "spend", "aggregation", "sum")));
        pivot.put("filters", List.of());
        pivot.put("sort", List.of());
        JsonNode grouped = api.postJson("/api/v1/reports/data?sort=description,desc",
                Map.of("type", "TABLE", "datasource", "transactions", "definition", pivot), 200);

        List<String> labels = new ArrayList<>();
        grouped.get("rows").forEach(r -> labels.add(r.get("values").get("description").asText()));
        assertTrue(labels.contains("Groceries") && labels.contains("UPI/SWIGGY/ORDER"), labels.toString());
        assertTrue(labels.indexOf("UPI/SWIGGY/ORDER") < labels.indexOf("Groceries"), labels.toString());
        assertTrue(!labels.contains("POS BIGBASKET"), labels.toString());
    }

    // ------------------------------------------------------------------ net worth group totals

    @Test
    void netWorthGroupTotalsAreEachSidesFigureOverEveryRowOnEveryPageAndSort() throws Exception {
        UUID card = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Rewards Card", "last4", "4321",
                "creditLimit", 100000, "anniversaryDate", today.toString(), "financialPosition", "liability",
                "excludeFromNetAsset", false));
        txn(card, "2500", "Card spend", null);

        JsonNode all = api.postJson("/api/v1/dashboards/builtins/net_worth/underlying?size=50", null, 200);
        JsonNode firstPage = api.postJson("/api/v1/dashboards/builtins/net_worth/underlying?size=1", null, 200);
        JsonNode sorted = api.postJson("/api/v1/dashboards/builtins/net_worth/underlying?size=1&sort=name,asc", null, 200);

        // Bank: 100000 − 800 debits; the card owes 2500 (negative in the Net value column).
        Map<String, BigDecimal> expected = new HashMap<>();
        all.get("table").get("rows").forEach(r -> expected.merge(r.get("side").asText(),
                r.get("signedValue").decimalValue(), BigDecimal::add));
        assertDecimal("99200", expected.get("asset"));
        assertDecimal("-2500", expected.get("liability"));
        for (JsonNode body : List.of(all, firstPage, sorted)) {
            JsonNode totals = body.get("groupTotals");
            assertEquals(2, totals.size(), totals.toString());
            assertDecimal("99200", totals.get("asset").decimalValue());
            assertDecimal("-2500", totals.get("liability").decimalValue());
        }
        assertEquals(1, firstPage.get("table").get("rows").size());
        // Kept for compatibility.
        assertEquals(2, all.get("summaryLines").size());
    }

    @Test
    void underlyingDataWithoutGroupingHasNoGroupTotals() throws Exception {
        JsonNode body = api.postJson("/api/v1/reports/underlying", adHocKpi(List.of()), 200);

        assertTrue(body.get("groupField").isNull());
        assertTrue(body.get("groupTotals").isObject());
        assertEquals(0, body.get("groupTotals").size());
    }

    // ------------------------------------------------------------------ money format

    @Test
    void theAmountKpiAndItsUnderlyingDataCarryTheCurrencyFormat() throws Exception {
        // The dashboard KPI exactly as saved: amount, sum, not excluded, the last 2 years, no comparison.
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", "amount");
        def.put("aggregation", "sum");
        def.put("filters", List.of(filter("isExcluded", "is", false),
                filter("date", "last_x_years", Map.of("amount", 2))));
        def.put("comparison", null);
        UUID reportId = api.create("/api/v1/reports", Map.of("name", "Total amount", "type", "KPI",
                "datasource", "transactions", "definition", def));

        JsonNode saved = api.postJson("/api/v1/reports/" + reportId + "/data", null, 200);
        JsonNode adHoc = api.postJson("/api/v1/reports/data",
                Map.of("type", "KPI", "datasource", "transactions", "definition", def), 200);
        JsonNode underlying = api.postJson("/api/v1/reports/" + reportId + "/underlying", null, 200);

        assertEquals("currency", saved.get("format").asText());
        assertEquals("currency", adHoc.get("format").asText());
        assertEquals("currency", underlying.get("format").asText());
        assertDecimal("-800", saved.get("value").decimalValue());
        assertDecimal("-800", underlying.get("value").decimalValue());
    }

    @Test
    void theBuiltInNetWorthKpiCarriesTheCurrencyFormat() throws Exception {
        JsonNode kpi = api.postJson("/api/v1/dashboards/builtins/net_worth/data", null, 200);
        JsonNode underlying = api.postJson("/api/v1/dashboards/builtins/net_worth/underlying", null, 200);

        assertEquals("currency", kpi.get("format").asText());
        assertEquals("currency", underlying.get("format").asText());
    }

    // ------------------------------------------------------------------ helpers

    private void assertListed(Map<String, Object> filter, String value, List<UUID> ids) throws Exception {
        JsonNode body = api.postJson("/api/v1/reports/underlying", adHocKpi(List.of(filter)), 200);
        assertDecimal(value, body.get("value").decimalValue());
        assertEquals(new java.util.HashSet<>(ids), new java.util.HashSet<>(rowIds(body.get("table"))), filter.toString());
    }

    /** Spend this month on Main Bank (plus {@code extra} filters), as an ad-hoc KPI request. */
    private static Map<String, Object> adHocKpi(List<Map<String, Object>> extra) {
        List<Map<String, Object>> filters = new ArrayList<>(List.of(
                filter("date", "this_month", null),
                filter("account", "is", "Main Bank")));
        filters.addAll(extra);
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", "spend");
        def.put("aggregation", "sum");
        def.put("filters", filters);
        return Map.of("type", "KPI", "datasource", "transactions", "definition", def);
    }

    private static Map<String, Object> filter(String field, String operator, Object value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", field);
        f.put("operator", operator);
        if (value != null) {
            f.put("value", value);
        }
        return f;
    }

    private UUID txn(UUID accountId, String amount, String description, String sourcedDescription) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction(account, today, new BigDecimal(amount), description,
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setSourcedDescription(sourcedDescription);
        t.setUser(account.getUser());
        return transactionRepository.save(t).getId();
    }

    private static List<UUID> rowIds(JsonNode table) {
        List<UUID> ids = new ArrayList<>();
        table.get("rows").forEach(r -> ids.add(UUID.fromString(r.get("id").asText())));
        return ids;
    }

    private static Map<UUID, JsonNode> rowsById(JsonNode table) {
        Map<UUID, JsonNode> rows = new HashMap<>();
        table.get("rows").forEach(r -> rows.put(UUID.fromString(r.get("id").asText()), r));
        return rows;
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " vs " + actual);
    }
}
