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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime header sort ({@code sort=<key>,<asc|desc>}) on the three table run endpoints, over real
 * persistence: it replaces the saved sort for that run only, follows the saved-sort key rules
 * (raw: a column; pivot: a row dimension, or a measure key without column dimensions), applies to
 * the SQL and the in-memory pivot alike, and answers 400 for an unsortable key or a malformed value.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportRunSortIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private UUID userId;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String email = "run-sort-it-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();

        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Sort Bank",
                "last4", "1234", "openingBalance", 50000, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID wallet = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Sort Wallet",
                "financialPosition", "asset", "excludeFromNetAsset", false));
        txn(bank, today.minusDays(3), "300", "Alpha");
        txn(bank, today.minusDays(2), "100", "Bravo");
        txn(bank, today.minusDays(1), "200", "Charlie");
        txn(bank, today, "50", "Alpha");
        txn(wallet, today.minusDays(4), "700", "Delta");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    // ------------------------------------------------------------------ saved raw table

    @Test
    void savedRawTable_runtimeSortReplacesTheSavedSortForThatRunOnly() throws Exception {
        UUID reportId = savedRaw();

        JsonNode saved = api.postJson("/api/v1/reports/" + reportId + "/data", null, 200);
        JsonNode bySpendAsc = api.postJson("/api/v1/reports/" + reportId + "/data?sort=spend,asc", null, 200);
        JsonNode bySpendDesc = api.postJson("/api/v1/reports/" + reportId + "/data?sort=spend,DESC", null, 200);
        JsonNode blank = api.postJson("/api/v1/reports/" + reportId + "/data?sort=", null, 200);

        assertEquals(List.of("Delta", "Alpha", "Bravo", "Charlie", "Alpha"), column(saved, "description"));
        assertEquals(List.of("50", "100", "200", "300", "700"), decimals(bySpendAsc, "spend"));
        assertEquals(List.of("700", "300", "200", "100", "50"), decimals(bySpendDesc, "spend"));
        assertEquals(column(saved, "description"), column(blank, "description"));
        // Never persisted: the report still carries its saved sort.
        JsonNode report = api.getJson("/api/v1/reports/" + reportId, 200);
        assertEquals("date", report.get("definition").get("sort").get(0).get("key").asText());
        assertEquals("asc", report.get("definition").get("sort").get(0).get("direction").asText());
    }

    @Test
    void savedRawTable_runtimeSortPagesInTheSortedOrder() throws Exception {
        UUID reportId = savedRaw();

        JsonNode first = api.postJson("/api/v1/reports/" + reportId + "/data?sort=spend,desc&page=0&size=2", null, 200);
        JsonNode second = api.postJson("/api/v1/reports/" + reportId + "/data?sort=spend,desc&page=1&size=2", null, 200);

        assertEquals(List.of("700", "300"), decimals(first, "spend"));
        assertEquals(List.of("200", "100"), decimals(second, "spend"));
    }

    @Test
    void savedRawTable_keyThatIsNotAColumnOrMalformedValueIs400() throws Exception {
        UUID reportId = savedRaw();

        assertBadRequest(api.post("/api/v1/reports/" + reportId + "/data?sort=account,asc", null),
                "Sort key is not an available column: account");
        assertBadRequest(api.post("/api/v1/reports/" + reportId + "/data?sort=spend", null), "Invalid sort");
        assertBadRequest(api.post("/api/v1/reports/" + reportId + "/data?sort=spend,up", null), "Invalid sort");
        assertBadRequest(api.post("/api/v1/reports/" + reportId + "/data?sort=spend,asc;date,desc", null), "Invalid sort");
    }

    @Test
    void savedKpiIgnoresTheRuntimeSortButStillRejectsAMalformedOne() throws Exception {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("measure", "spend");
        kpi.put("aggregation", "sum");
        kpi.put("filters", List.of(Map.of("field", "type", "operator", "is", "value", "DEBIT")));
        UUID reportId = api.create("/api/v1/reports", Map.of("name", "Spend", "type", "KPI",
                "datasource", "transactions", "definition", kpi));

        JsonNode sorted = api.postJson("/api/v1/reports/" + reportId + "/data?sort=anything,asc", null, 200);

        assertEquals(0, new BigDecimal("1350").compareTo(sorted.get("value").decimalValue()));
        assertEquals(400, api.post("/api/v1/reports/" + reportId + "/data?sort=anything", null).getStatus());
    }

    // ------------------------------------------------------------------ ad-hoc pivots

    @Test
    void adHocSqlPivot_sortsByRowDimensionOrByMeasureKey() throws Exception {
        Map<String, Object> request = pivot("transactions", "description", "spend", List.of());

        JsonNode byMeasure = api.postJson("/api/v1/reports/data?sort=spend_sum,desc", request, 200);
        JsonNode byDimension = api.postJson("/api/v1/reports/data?sort=description,desc", request, 200);

        assertEquals(List.of("Delta", "Alpha", "Charlie", "Bravo"), pivotLabels(byMeasure, "description"));
        assertEquals(List.of("700", "350", "200", "100"), pivotMeasure(byMeasure, "spend_sum"));
        assertEquals(List.of("Delta", "Charlie", "Bravo", "Alpha"), pivotLabels(byDimension, "description"));
    }

    @Test
    void adHocPivotWithColumnDimensions_rejectsAMeasureKeyAndAColumnDimension() throws Exception {
        Map<String, Object> request = pivot("transactions", "description", "spend",
                List.of(Map.of("field", "account")));

        assertBadRequest(api.post("/api/v1/reports/data?sort=spend_sum,desc", request),
                "Sort key is not an available column: spend_sum");
        assertBadRequest(api.post("/api/v1/reports/data?sort=account,asc", request),
                "Sort key is not an available column: account");
        assertEquals(200, api.post("/api/v1/reports/data?sort=description,asc", request).getStatus());
    }

    @Test
    void adHocInMemoryPivot_honoursTheRuntimeSort() throws Exception {
        Map<String, Object> request = pivot("net_worth", "name", "value", List.of());

        JsonNode asc = api.postJson("/api/v1/reports/data?sort=value_sum,asc", request, 200);
        JsonNode desc = api.postJson("/api/v1/reports/data?sort=name,desc", request, 200);

        // Bank: 50000 − 650 = 49350; wallet: −700, listed as a 700 liability.
        assertEquals(List.of("Sort Wallet", "Sort Bank"), pivotLabels(asc, "name"));
        assertEquals(List.of("700", "49350"), pivotMeasure(asc, "value_sum"));
        assertEquals(List.of("Sort Wallet", "Sort Bank"), pivotLabels(desc, "name"));
        assertBadRequest(api.post("/api/v1/reports/data?sort=kind,asc", request),
                "Sort key is not an available column: kind");
    }

    // ------------------------------------------------------------------ built-in table

    @Test
    void builtinUpcoming_runtimeSortReplacesItsDueDateOrder() throws Exception {
        lending("Asha", "900", today.plusDays(5));
        lending("Bala", "500", today.plusDays(3));

        JsonNode byDue = api.postJson("/api/v1/dashboards/builtins/upcoming/data", null, 200);
        JsonNode byAmount = api.postJson("/api/v1/dashboards/builtins/upcoming/data?sort=amount,desc", Map.of(), 200);

        assertEquals(List.of("500", "900"), lendingReturns(byDue));
        assertEquals(List.of("900", "500"), lendingReturns(byAmount));
        assertBadRequest(api.post("/api/v1/dashboards/builtins/upcoming/data?sort=kind,asc", null),
                "Sort key is not an available column: kind");
        assertBadRequest(api.post("/api/v1/dashboards/builtins/upcoming/data?sort=amount,sideways", null),
                "Invalid sort");
    }

    // ------------------------------------------------------------------ helpers

    private UUID savedRaw() throws Exception {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("mode", "raw");
        def.put("columns", List.of("date", "description", "spend"));
        def.put("filters", List.of(Map.of("field", "type", "operator", "is", "value", "DEBIT")));
        def.put("sort", List.of(Map.of("key", "date", "direction", "asc")));
        return api.create("/api/v1/reports", Map.of("name", "Spends", "type", "TABLE",
                "datasource", "transactions", "definition", def));
    }

    private static Map<String, Object> pivot(String datasource, String row, String measure, List<Map<String, Object>> columns) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("mode", "aggregated");
        def.put("rows", List.of(Map.of("field", row)));
        def.put("columns", columns);
        def.put("measures", List.of(Map.of("field", measure, "aggregation", "sum")));
        def.put("filters", List.of());
        def.put("sort", List.of());
        return Map.of("type", "TABLE", "datasource", datasource, "definition", def);
    }

    private void lending(String counterparty, String amount, LocalDate expectedReturn) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("newCounterpartyName", counterparty);
        body.put("direction", "lent");
        body.put("amount", amount);
        body.put("entryDate", today.minusDays(10).toString());
        body.put("expectedReturnDate", expectedReturn.toString());
        api.create("/api/v1/lendings", body);
    }

    private void txn(UUID accountId, LocalDate date, String amount, String description) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction(account, date, new BigDecimal(amount), description,
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setUser(account.getUser());
        transactionRepository.save(t);
    }

    private static List<String> column(JsonNode table, String key) {
        List<String> values = new ArrayList<>();
        table.get("rows").forEach(r -> values.add(r.get(key).asText()));
        return values;
    }

    private static List<String> decimals(JsonNode table, String key) {
        List<String> values = new ArrayList<>();
        table.get("rows").forEach(r -> values.add(r.get(key).decimalValue().stripTrailingZeros().toPlainString()));
        return values;
    }

    /** A pivot's row labels for {@code field}, in row order. */
    private static List<String> pivotLabels(JsonNode pivot, String field) {
        List<String> values = new ArrayList<>();
        pivot.get("rows").forEach(r -> values.add(r.get("values").get(field).asText()));
        return values;
    }

    /** A flat pivot's (no column dimensions) {@code measureKey} value per row, in row order. */
    private static List<String> pivotMeasure(JsonNode pivot, String measureKey) {
        List<String> values = new ArrayList<>();
        pivot.get("rows").forEach(r -> {
            JsonNode cells = r.get("cells");
            assertEquals(1, cells.size(), "flat pivot has one cell group per row");
            values.add(cells.elements().next().get(measureKey).decimalValue().stripTrailingZeros().toPlainString());
        });
        return values;
    }

    /** The amounts of the lending-return rows, in the listed order. */
    private static List<String> lendingReturns(JsonNode table) {
        List<String> values = new ArrayList<>();
        table.get("rows").forEach(r -> {
            if (r.get("amount") != null && r.get("amount").isNumber()) {
                values.add(r.get("amount").decimalValue().stripTrailingZeros().toPlainString());
            }
        });
        return values;
    }

    private static void assertBadRequest(MockHttpServletResponse response, String message) throws Exception {
        assertEquals(400, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentAsString().contains(message), response.getContentAsString());
    }
}
