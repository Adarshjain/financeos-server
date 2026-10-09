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
import com.financeos.domain.user.User;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "View underlying data" end to end over real persistence: the six VUD endpoints (saved, ad-hoc
 * and built-in; JSON and CSV) list exactly the rows a KPI period is computed from and report the
 * KPI's own value for that period (current and previous; SUM and the MIN/MAX winner rows), scoped
 * to the caller, and reject what the contract rejects.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KpiUnderlyingIntegrationTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private UUID bankId;

    private LocalDate today;
    private LocalDate monthStart;
    private LocalDate previousMonthStart;
    private LocalDate previousMonthEnd;

    /** Current-month debits (spend 300, 500, 500 — a tie for MAX), previous-month debits (200, 700). */
    private UUID d300;
    private UUID d500a;
    private UUID d500b;
    private UUID p200;
    private UUID p700;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        monthStart = today.withDayOfMonth(1);
        previousMonthStart = monthStart.minusMonths(1);
        previousMonthEnd = monthStart.minusDays(1);

        String email = "vud-it-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "vud-it-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();

        bankId = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main Bank",
                "last4", "1234", "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));

        d300 = txn(bankId, monthStart, "300", "Groceries", TransactionType.DEBIT);
        d500a = txn(bankId, today, "500", "Fuel", TransactionType.DEBIT);
        d500b = txn(bankId, today, "500", "Dinner", TransactionType.DEBIT);
        txn(bankId, today, "1000", "Salary", TransactionType.CREDIT);
        p200 = txn(bankId, previousMonthStart, "200", "Books", TransactionType.DEBIT);
        p700 = txn(bankId, previousMonthEnd, "700", "Rent share", TransactionType.DEBIT);
        txn(bankId, monthStart.minusMonths(2), "999", "Two months ago", TransactionType.DEBIT);

        UUID otherBank = otherApi.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Other Bank",
                "last4", "9999", "openingBalance", 5000, "financialPosition", "asset", "excludeFromNetAsset", false));
        txn(otherBank, today, "12345", "Not mine", TransactionType.DEBIT);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    // ------------------------------------------------------------------ saved report

    @Test
    void savedKpi_currentPeriodListsExactlyItsRowsAndEqualsTheKpiValue() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));
        JsonNode kpi = api.postJson("/api/v1/reports/" + reportId + "/data", null, 200);

        JsonNode body = api.postJson("/api/v1/reports/" + reportId + "/underlying", null, 200);

        assertDecimal(kpi.get("value"), body.get("value"));
        assertDecimal("1300", body.get("value"));
        assertEquals("current", body.get("period").asText());
        assertEquals(monthStart.toString(), body.get("range").get("from").asText());
        assertEquals(monthStart.plusMonths(1).minusDays(1).toString(), body.get("range").get("to").asText());
        assertTrue(body.get("previousAvailable").asBoolean());
        assertEquals(previousMonthStart.toString(), body.get("previousRange").get("from").asText());
        assertEquals(previousMonthEnd.toString(), body.get("previousRange").get("to").asText());
        assertEquals("spend", body.get("measure").asText());
        assertEquals("Spend", body.get("measureLabel").asText());
        assertEquals("sum", body.get("aggregation").asText());
        assertEquals("currency", body.get("format").asText());
        assertEquals(3, body.get("rowCount").asLong());
        assertFalse(body.get("winnerOnly").asBoolean());
        assertEquals(0, body.get("summaryLines").size());
        assertEquals(0, body.get("notCounted").size());
        assertEquals("transaction", body.get("rowAction").asText());
        assertTrue(body.get("groupField").isNull());
        assertTrue(body.get("sortKey").isNull());
        assertTrue(body.get("sortDirection").isNull());

        assertEquals(List.of("date", "description", "account", "category", "spend"), columnKeys(body.get("table")));
        // Default order: newest first; the two same-day rows are both before the 1st of the month.
        List<UUID> ids = rowIds(body.get("table"));
        assertEquals(Set.of(d300, d500a, d500b), Set.copyOf(ids));
        if (!today.equals(monthStart)) {
            assertEquals(d300, ids.get(2));
        }
        for (JsonNode row : body.get("table").get("rows")) {
            assertEquals("Main Bank", row.get("account").asText());
        }

        assertEquals(List.of(
                        List.of("date", "Date", "This month"),
                        List.of("type", "Type", "is DEBIT"),
                        List.of("account", "Account", "is Main Bank")),
                chips(body));
    }

    @Test
    void savedKpi_previousPeriodListsThePreviousRowsAndEqualsTheComparisonValue() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));
        JsonNode kpi = api.postJson("/api/v1/reports/" + reportId + "/data", null, 200);

        JsonNode body = api.postJson("/api/v1/reports/" + reportId + "/underlying?period=previous", null, 200);

        assertDecimal(kpi.get("comparison").get("previousValue"), body.get("value"));
        assertDecimal("900", body.get("value"));
        assertEquals("previous", body.get("period").asText());
        assertEquals(previousMonthStart.toString(), body.get("range").get("from").asText());
        assertEquals(previousMonthEnd.toString(), body.get("range").get("to").asText());
        assertEquals(Set.of(p200, p700), Set.copyOf(rowIds(body.get("table"))));
        assertEquals(List.of(p700, p200), rowIds(body.get("table")));
        assertEquals(List.of("Between " + DAY.format(previousMonthStart) + " and " + DAY.format(previousMonthEnd),
                        "is DEBIT", "is Main Bank"),
                chips(body).stream().map(c -> c.get(2)).toList());
    }

    @Test
    void savedKpi_maxListsOnlyTheWinningRowsWithEveryTie() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("max"));
        JsonNode kpi = api.postJson("/api/v1/reports/" + reportId + "/data", null, 200);

        JsonNode current = api.postJson("/api/v1/reports/" + reportId + "/underlying", null, 200);
        JsonNode previous = api.postJson("/api/v1/reports/" + reportId + "/underlying?period=previous", null, 200);

        assertDecimal(kpi.get("value"), current.get("value"));
        assertDecimal("500", current.get("value"));
        assertTrue(current.get("winnerOnly").asBoolean());
        assertEquals(2, current.get("rowCount").asLong());
        assertEquals(Set.of(d500a, d500b), Set.copyOf(rowIds(current.get("table"))));

        assertDecimal(kpi.get("comparison").get("previousValue"), previous.get("value"));
        assertDecimal("700", previous.get("value"));
        assertEquals(List.of(p700), rowIds(previous.get("table")));
    }

    @Test
    void savedKpi_runtimeSortOrdersByAListedColumnAndIsEchoed() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));

        JsonNode asc = api.postJson("/api/v1/reports/" + reportId + "/underlying?sort=spend,ASC", null, 200);
        JsonNode desc = api.postJson("/api/v1/reports/" + reportId + "/underlying?sort=description,desc", null, 200);

        assertEquals("spend", asc.get("sortKey").asText());
        assertEquals("asc", asc.get("sortDirection").asText());
        assertEquals(d300, rowIds(asc.get("table")).get(0));
        assertEquals(List.of("Groceries", "Fuel", "Dinner"), column(desc.get("table"), "description"));
        assertEquals("description", desc.get("sortKey").asText());
        assertEquals("desc", desc.get("sortDirection").asText());
    }

    @Test
    void savedKpi_sortOnAColumnThatIsNotListedOrMalformedIs400() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));

        assertEquals(400, api.post("/api/v1/reports/" + reportId + "/underlying?sort=amount,asc", null).getStatus());
        assertEquals(400, api.post("/api/v1/reports/" + reportId + "/underlying?sort=spend", null).getStatus());
        assertEquals(400, api.post("/api/v1/reports/" + reportId + "/underlying/csv?sort=amount,asc", null).getStatus());
    }

    @Test
    void savedKpi_pagesTheRowsServerSide() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));

        JsonNode first = api.postJson("/api/v1/reports/" + reportId + "/underlying?sort=spend,asc&page=0&size=2", null, 200);
        JsonNode second = api.postJson("/api/v1/reports/" + reportId + "/underlying?sort=spend,asc&page=1&size=2", null, 200);

        assertEquals(2, first.get("table").get("rows").size());
        assertEquals(1, second.get("table").get("rows").size());
        assertEquals(3, second.get("table").get("page").get("totalElements").asLong());
        assertEquals(2, second.get("table").get("page").get("totalPages").asInt());
        assertEquals(3, second.get("rowCount").asLong());
        Set<UUID> all = new java.util.HashSet<>(rowIds(first.get("table")));
        all.addAll(rowIds(second.get("table")));
        assertEquals(Set.of(d300, d500a, d500b), all);
    }

    @Test
    void savedKpi_csvHasEveryRowInTheListedOrderAsAnAttachment() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));
        JsonNode json = api.postJson("/api/v1/reports/" + reportId + "/underlying?sort=spend,desc", null, 200);

        MockHttpServletResponse csv = api.post("/api/v1/reports/" + reportId + "/underlying/csv?sort=spend,desc", null);

        assertCsvAttachment(csv);
        List<String> lines = csvLines(csv);
        assertEquals("Date,Description,Account,Category,Spend", lines.get(0));
        assertEquals(4, lines.size());
        List<String> descriptions = column(json.get("table"), "description");
        for (int i = 0; i < 3; i++) {
            assertTrue(lines.get(i + 1).contains("," + descriptions.get(i) + ",Main Bank,"), lines.get(i + 1));
        }
        assertEquals(DAY.format(monthStart) + ",Groceries,Main Bank,,300.00", lines.get(3));
    }

    @Test
    void savedKpi_previousPeriodCsvListsThePreviousRows() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));

        MockHttpServletResponse csv = api.post("/api/v1/reports/" + reportId + "/underlying/csv?period=previous", null);

        assertCsvAttachment(csv);
        assertEquals(List.of("Date,Description,Account,Category,Spend",
                        DAY.format(previousMonthEnd) + ",Rent share,Main Bank,,700.00",
                        DAY.format(previousMonthStart) + ",Books,Main Bank,,200.00"),
                csvLines(csv));
    }

    /** Ownership is the saved run's: another user's report is refused exactly as running it is. */
    @Test
    void anotherUsersReportIsRefusedLikeRunningItAndAnUnknownOneIsNotFound() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));
        MockHttpServletResponse run = otherApi.post("/api/v1/reports/" + reportId + "/data", null);

        MockHttpServletResponse json = otherApi.post("/api/v1/reports/" + reportId + "/underlying", null);
        MockHttpServletResponse csv = otherApi.post("/api/v1/reports/" + reportId + "/underlying/csv", null);

        assertEquals(400, run.getStatus());
        assertEquals(run.getStatus(), json.getStatus());
        assertTrue(json.getContentAsString().contains("You do not have permission to access this report."));
        assertFalse(json.getContentAsString().contains("Groceries"));
        assertEquals(run.getStatus(), csv.getStatus());
        assertFalse(String.valueOf(csv.getContentType()).startsWith("text/csv"));
        assertEquals(404, api.post("/api/v1/reports/" + UUID.randomUUID() + "/underlying", null).getStatus());
        assertEquals(404, api.post("/api/v1/reports/" + UUID.randomUUID() + "/underlying/csv", null).getStatus());
    }

    @Test
    void nonKpiSavedReportIs400() throws Exception {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("mode", "raw");
        table.put("columns", List.of("date", "description", "spend"));
        table.put("filters", List.of());
        table.put("sort", List.of());
        UUID reportId = saveReport("TABLE", table);

        MockHttpServletResponse json = api.post("/api/v1/reports/" + reportId + "/underlying", null);
        MockHttpServletResponse csv = api.post("/api/v1/reports/" + reportId + "/underlying/csv", null);

        assertEquals(400, json.getStatus());
        assertTrue(json.getContentAsString().contains("Underlying data is only available for KPI reports"));
        assertEquals(400, csv.getStatus());
        assertFalse(String.valueOf(csv.getContentType()).startsWith("text/csv"));
    }

    @Test
    void kpiWithoutADateFilterHasNoPreviousPeriod() throws Exception {
        Map<String, Object> def = kpi("spend", "sum", List.of(filter("type", "is", "DEBIT")));
        UUID reportId = saveReport("KPI", def);
        JsonNode kpi = api.postJson("/api/v1/reports/" + reportId + "/data", null, 200);

        JsonNode current = api.postJson("/api/v1/reports/" + reportId + "/underlying", null, 200);
        MockHttpServletResponse previous = api.post("/api/v1/reports/" + reportId + "/underlying?period=previous", null);
        MockHttpServletResponse previousCsv = api.post("/api/v1/reports/" + reportId + "/underlying/csv?period=previous", null);

        assertDecimal(kpi.get("value"), current.get("value"));
        assertDecimal("3199", current.get("value"));
        assertFalse(current.get("previousAvailable").asBoolean());
        assertTrue(current.get("range").isNull());
        assertTrue(current.get("previousRange").isNull());
        assertEquals(6, current.get("rowCount").asLong());
        assertEquals(400, previous.getStatus());
        assertTrue(previous.getContentAsString().contains("This KPI has no previous period"));
        assertEquals(400, previousCsv.getStatus());
    }

    @Test
    void unknownPeriodIs400() throws Exception {
        UUID reportId = saveReport("KPI", spendThisMonth("sum"));

        assertEquals(400, api.post("/api/v1/reports/" + reportId + "/underlying?period=next", null).getStatus());
    }

    // ------------------------------------------------------------------ ad-hoc

    @Test
    void adHocKpi_jsonAndCsvMatchTheAdHocRun() throws Exception {
        Map<String, Object> request = Map.of("type", "KPI", "datasource", "transactions",
                "definition", spendThisMonth("sum"));
        JsonNode kpi = api.postJson("/api/v1/reports/data", request, 200);

        JsonNode current = api.postJson("/api/v1/reports/underlying", request, 200);
        JsonNode previous = api.postJson("/api/v1/reports/underlying?period=previous", request, 200);
        MockHttpServletResponse csv = api.post("/api/v1/reports/underlying/csv?sort=spend,asc", request);

        assertDecimal(kpi.get("value"), current.get("value"));
        assertDecimal(kpi.get("comparison").get("previousValue"), previous.get("value"));
        assertEquals(Set.of(d300, d500a, d500b), Set.copyOf(rowIds(current.get("table"))));
        assertEquals(Set.of(p200, p700), Set.copyOf(rowIds(previous.get("table"))));
        assertCsvAttachment(csv);
        List<String> lines = csvLines(csv);
        assertEquals(4, lines.size());
        assertEquals(DAY.format(monthStart) + ",Groceries,Main Bank,,300.00", lines.get(1));
    }

    @Test
    void adHocKpi_minListsTheWinningRow() throws Exception {
        Map<String, Object> request = Map.of("type", "KPI", "datasource", "transactions",
                "definition", spendThisMonth("min"));
        JsonNode kpi = api.postJson("/api/v1/reports/data", request, 200);

        JsonNode body = api.postJson("/api/v1/reports/underlying", request, 200);

        assertDecimal(kpi.get("value"), body.get("value"));
        assertDecimal("300", body.get("value"));
        assertTrue(body.get("winnerOnly").asBoolean());
        assertEquals(List.of(d300), rowIds(body.get("table")));
    }

    @Test
    void adHocNonKpiIs400() throws Exception {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("mode", "raw");
        chart.put("columns", List.of("date", "spend"));
        chart.put("filters", List.of());
        Map<String, Object> request = Map.of("type", "TABLE", "datasource", "transactions", "definition", chart);

        assertEquals(400, api.post("/api/v1/reports/underlying", request).getStatus());
        assertEquals(400, api.post("/api/v1/reports/underlying/csv", request).getStatus());
    }

    // ------------------------------------------------------------------ built-in

    @Test
    void builtinNetWorth_listsTheCountedItemsWithTotalsAndWhatWasLeftOut() throws Exception {
        UUID excluded = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Hidden Wallet",
                "financialPosition", "asset", "excludeFromNetAsset", true));
        UUID closed = api.create("/api/v1/accounts", Map.of("type", "generic", "name", "Old Wallet",
                "financialPosition", "asset", "excludeFromNetAsset", false));
        jdbc.update("UPDATE accounts SET closed_on = ? WHERE id = ?", today.minusDays(1), closed.toString());
        UUID card = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Rewards Card", "last4", "4321",
                "creditLimit", 100000, "anniversaryDate", today.toString(), "financialPosition", "liability",
                "excludeFromNetAsset", false));
        txn(card, today, "2500", "Card spend", TransactionType.DEBIT);
        JsonNode kpi = api.postJson("/api/v1/dashboards/builtins/net_worth/data", null, 200);

        JsonNode body = api.postJson("/api/v1/dashboards/builtins/net_worth/underlying", null, 200);

        // 100000 opening + 1000 credit − 3199 debits on the bank; the card owes 2500.
        assertDecimal(kpi.get("value"), body.get("value"));
        assertDecimal("95301", body.get("value"));
        assertEquals("signedValue", body.get("measure").asText());
        assertEquals("breakdown", body.get("rowAction").asText());
        assertEquals("side", body.get("groupField").asText());
        assertEquals(List.of("name", "kind", "side", "signedValue"), columnKeys(body.get("table")));
        assertEquals(List.of(bankId, card), rowIds(body.get("table")));
        assertEquals(List.of("asset", "liability"), column(body.get("table"), "side"));
        assertEquals(2, body.get("rowCount").asLong());

        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        body.get("summaryLines").forEach(l -> summary.put(l.get("label").asText(), l.get("value").decimalValue()));
        assertEquals(List.of("Assets", "Liabilities"), List.copyOf(summary.keySet()));
        assertEquals(0, new BigDecimal("97801").compareTo(summary.get("Assets")));
        assertEquals(0, new BigDecimal("2500").compareTo(summary.get("Liabilities")));

        Map<String, JsonNode> notCounted = new LinkedHashMap<>();
        body.get("notCounted").forEach(n -> notCounted.put(n.get("id").asText(), n));
        assertEquals(Set.of(excluded.toString(), closed.toString()), notCounted.keySet());
        assertEquals("excluded", notCounted.get(excluded.toString()).get("reason").asText());
        assertEquals("Excluded from net worth", notCounted.get(excluded.toString()).get("reasonLabel").asText());
        assertEquals("closed", notCounted.get(closed.toString()).get("reason").asText());
        assertEquals("Closed on " + DAY.format(today.minusDays(1)),
                notCounted.get(closed.toString()).get("reasonLabel").asText());
    }

    @Test
    void builtinNetWorth_csvAndSort() throws Exception {
        UUID card = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Rewards Card", "last4", "4321",
                "creditLimit", 100000, "anniversaryDate", today.toString(), "financialPosition", "liability",
                "excludeFromNetAsset", false));
        txn(card, today, "2500", "Card spend", TransactionType.DEBIT);

        JsonNode sorted = api.postJson("/api/v1/dashboards/builtins/net_worth/underlying?sort=signedValue,asc", null, 200);
        MockHttpServletResponse csv = api.post("/api/v1/dashboards/builtins/net_worth/underlying/csv", Map.of());

        assertEquals(List.of(card, bankId), rowIds(sorted.get("table")));
        assertEquals("signedValue", sorted.get("sortKey").asText());
        assertCsvAttachment(csv);
        assertEquals(List.of("Name,Kind,Side,Net value", "Main Bank,bank_account,asset,97801.00", "Rewards Card,credit_card,liability,-2500.00"),
                csvLines(csv));
        assertEquals(400, api.post("/api/v1/dashboards/builtins/net_worth/underlying?sort=value,asc", null).getStatus());
    }

    @Test
    void builtinThatIsNotAKpiTemplateIs400AndAnUnknownOneIs404() throws Exception {
        assertEquals(400, api.post("/api/v1/dashboards/builtins/upcoming/underlying", null).getStatus());
        assertEquals(400, api.post("/api/v1/dashboards/builtins/upcoming/underlying/csv", null).getStatus());
        assertEquals(400, api.post("/api/v1/dashboards/builtins/attention/underlying", null).getStatus());
        assertEquals(400, api.post("/api/v1/dashboards/builtins/attention/underlying/csv", null).getStatus());
        assertEquals(404, api.post("/api/v1/dashboards/builtins/nope/underlying", null).getStatus());
        assertEquals(404, api.post("/api/v1/dashboards/builtins/nope/underlying/csv", null).getStatus());
    }

    // ------------------------------------------------------------------ helpers

    private UUID txn(UUID accountId, LocalDate date, String amount, String description, TransactionType type) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        User owner = account.getUser();
        Transaction t = new Transaction(account, date, new BigDecimal(amount), description,
                TransactionSource.manual, type, false, false);
        t.setUser(owner);
        return transactionRepository.save(t).getId();
    }

    private Map<String, Object> spendThisMonth(String aggregation) {
        return kpi("spend", aggregation, List.of(
                filter("date", "this_month", null),
                filter("type", "is", "DEBIT"),
                filter("account", "is", "Main Bank")));
    }

    private static Map<String, Object> kpi(String measure, String aggregation, List<Map<String, Object>> filters) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", measure);
        def.put("aggregation", aggregation);
        def.put("filters", filters);
        def.put("comparison", Map.of("enabled", true, "period", "previous_period", "higherIsBetter", false));
        return def;
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

    private UUID saveReport(String type, Map<String, Object> definition) throws Exception {
        return api.create("/api/v1/reports", Map.of("name", "VUD " + type, "type", type,
                "datasource", "transactions", "definition", definition));
    }

    private static void assertDecimal(JsonNode expected, JsonNode actual) {
        assertEquals(0, expected.decimalValue().compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static List<String> columnKeys(JsonNode table) {
        List<String> keys = new ArrayList<>();
        table.get("columns").forEach(c -> keys.add(c.get("key").asText()));
        return keys;
    }

    private static List<UUID> rowIds(JsonNode table) {
        List<UUID> ids = new ArrayList<>();
        table.get("rows").forEach(r -> ids.add(UUID.fromString(r.get("id").asText())));
        return ids;
    }

    private static List<String> column(JsonNode table, String key) {
        List<String> values = new ArrayList<>();
        table.get("rows").forEach(r -> values.add(r.get(key).asText()));
        return values;
    }

    private static List<List<String>> chips(JsonNode body) {
        List<List<String>> chips = new ArrayList<>();
        body.get("filters").forEach(c -> chips.add(List.of(
                c.get("field").asText(), c.get("fieldLabel").asText(), c.get("text").asText())));
        return chips;
    }

    private static void assertCsvAttachment(MockHttpServletResponse csv) {
        assertEquals(200, csv.getStatus());
        assertEquals("text/csv;charset=UTF-8", csv.getContentType().replace(" ", ""));
        assertEquals("attachment; filename=\"underlying.csv\"", csv.getHeader("Content-Disposition"));
        byte[] bytes = csv.getContentAsByteArray();
        assertArrayEquals(BOM, java.util.Arrays.copyOf(bytes, 3));
    }

    /** The CSV's lines without the BOM, split on CRLF (every line ends with one). */
    private static List<String> csvLines(MockHttpServletResponse csv) {
        byte[] bytes = csv.getContentAsByteArray();
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\r\n"), "rows end with CRLF");
        assertFalse(text.replace("\r\n", "").contains("\n"), "no bare LF");
        return List.of(text.substring(0, text.length() - 2).split("\r\n", -1));
    }
}
