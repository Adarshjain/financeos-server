package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionCategory;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A transactions report filtered on category counts each transaction once however many of its
 * categories match (it used to be counted once per matching category): KPI values, the KPI's
 * underlying rows and CSV, raw tables, pivots and charts all agree, over real persistence.
 *
 * <p>Fixture (all debits today): Food+Fuel 100, Food 50, Travel+Shopping 30, uncategorised 20,
 * Food+Travel 40.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportCategoryFilterIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRepository categoryRepository;

    private ApiTestClient api;
    private UUID userId;
    private final Map<String, Category> categories = new HashMap<>();

    private UUID foodFuel;
    private UUID food;
    private UUID travelShopping;
    private UUID uncategorised;
    private UUID foodTravel;

    @BeforeEach
    void setUp() throws Exception {
        String email = "category-filter-it-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main Bank", "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        Account account = accountRepository.findById(bank).orElseThrow();
        for (String name : List.of("Food", "Fuel", "Travel", "Shopping")) {
            categories.put(name, categoryRepository.save(new Category(name, account.getUser())));
        }
        foodFuel = txn(account, "100", "Fuel and snacks", "Food", "Fuel");
        food = txn(account, "50", "Lunch", "Food");
        travelShopping = txn(account, "30", "Airport shop", "Travel", "Shopping");
        uncategorised = txn(account, "20", "Unknown");
        foodTravel = txn(account, "40", "Train meal", "Food", "Travel");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    // ------------------------------------------------------------------ KPI + underlying data

    @Test
    void kpiInSeveralCategoriesCountsATransactionInManyOfThemOnce() throws Exception {
        Map<String, Object> request = kpi("sum", filter("in", List.of("Food", "Fuel")));

        JsonNode kpi = api.postJson("/api/v1/reports/data", request, 200);
        JsonNode count = api.postJson("/api/v1/reports/data", kpi("count", filter("in", List.of("Food", "Fuel"))), 200);
        JsonNode underlying = api.postJson("/api/v1/reports/underlying", request, 200);

        assertDecimal("190", kpi.get("value"));
        assertDecimal("3", count.get("value"));
        assertDecimal("190", underlying.get("value"));
        assertEquals(3, underlying.get("rowCount").asLong());
        List<UUID> ids = rowIds(underlying.get("table"));
        assertEquals(Set.of(foodFuel, food, foodTravel), new HashSet<>(ids));
        assertEquals(ids.size(), new HashSet<>(ids).size(), "each transaction is listed once");
        assertEquals(0, sum(underlying.get("table"), "spend").compareTo(kpi.get("value").decimalValue()));
    }

    @Test
    void kpiNotInACategoryLeavesOutEveryTransactionInItAndCountsTheRestOnce() throws Exception {
        JsonNode isNot = api.postJson("/api/v1/reports/data", kpi("sum", filter("is_not", "Food")), 200);
        JsonNode notIn = api.postJson("/api/v1/reports/data", kpi("sum", filter("not_in", List.of("Food", "Fuel"))), 200);
        JsonNode underlying = api.postJson("/api/v1/reports/underlying", kpi("sum", filter("is_not", "Food")), 200);

        assertDecimal("50", isNot.get("value"));
        assertDecimal("50", notIn.get("value"));
        assertEquals(sorted(List.of(travelShopping, uncategorised)), sorted(rowIds(underlying.get("table"))));
        assertDecimal("50", underlying.get("value"));
    }

    @Test
    void kpiInOneCategoryIsUnchanged() throws Exception {
        JsonNode kpi = api.postJson("/api/v1/reports/data", kpi("sum", filter("is", "Food")), 200);

        assertDecimal("190", kpi.get("value"));
    }

    @Test
    void underlyingCsvListsEachTransactionOnce() throws Exception {
        MockHttpServletResponse csv = api.post("/api/v1/reports/underlying/csv", kpi("sum", filter("in", List.of("Food", "Fuel"))));

        assertEquals(200, csv.getStatus());
        String[] lines = csv.getContentAsString(StandardCharsets.UTF_8).split("\r\n");
        assertEquals(4, lines.length, String.join("\n", lines));
        assertTrue(lines[0].endsWith("Date,Description,Account,Category,Spend"), lines[0]);
    }

    // ------------------------------------------------------------------ tables + charts

    @Test
    void rawTableListsEachMatchingTransactionOnceAndCountsThemOnce() throws Exception {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("mode", "raw");
        def.put("columns", List.of("description", "category", "spend"));
        def.put("filters", List.of(filter("in", List.of("Food", "Fuel", "Travel"))));
        def.put("sort", List.of());

        JsonNode table = api.postJson("/api/v1/reports/data?size=2",
                Map.of("type", "TABLE", "datasource", "transactions", "definition", def), 200);
        JsonNode all = api.postJson("/api/v1/reports/data?size=50",
                Map.of("type", "TABLE", "datasource", "transactions", "definition", def), 200);

        assertEquals(4, table.get("page").get("totalElements").asLong());
        assertEquals(2, table.get("page").get("totalPages").asInt());
        List<UUID> ids = rowIds(all);
        assertEquals(List.of(foodFuel, food, travelShopping, foodTravel).stream().sorted().toList(), sorted(ids));
        for (JsonNode row : all.get("rows")) {
            if (row.get("id").asText().equals(foodFuel.toString())) {
                assertEquals("Food, Fuel", row.get("category").asText());
            }
        }
    }

    @Test
    void pivotNotGroupedByCategoryCountsEachTransactionOnce() throws Exception {
        JsonNode pivot = api.postJson("/api/v1/reports/data",
                pivot("type", filter("in", List.of("Food", "Fuel"))), 200);

        assertEquals(Map.of("DEBIT", "190"), pivotSums(pivot, "type"));
    }

    @Test
    void pivotGroupedByCategoryShowsOnlyTheFilteredCategoriesEachTransactionUnderEachOfThem() throws Exception {
        JsonNode in = api.postJson("/api/v1/reports/data", pivot("category", filter("in", List.of("Food", "Fuel"))), 200);
        JsonNode isNot = api.postJson("/api/v1/reports/data", pivot("category", filter("is_not", "Food")), 200);

        assertEquals(Map.of("Food", "190", "Fuel", "100"), pivotSums(in, "category"));
        assertEquals(Map.of("Travel", "30", "Shopping", "30", "(none)", "20"), pivotSums(isNot, "category"));
    }

    @Test
    void chartNotGroupedByCategoryCountsEachTransactionOnce() throws Exception {
        JsonNode chart = api.postJson("/api/v1/reports/data", chart("type", filter("in", List.of("Food", "Fuel"))), 200);

        assertEquals(List.of("DEBIT"), texts(chart.get("categories")));
        assertDecimal("190", chart.get("series").get(0).get("data").get(0));
        assertEquals(3, chart.get("meta").get("rowCount").asLong());
    }

    @Test
    void chartGroupedByCategoryShowsOnlyTheFilteredCategories() throws Exception {
        JsonNode chart = api.postJson("/api/v1/reports/data", chart("category", filter("in", List.of("Food", "Travel"))), 200);

        Map<String, String> byCategory = new HashMap<>();
        List<String> names = texts(chart.get("categories"));
        for (int i = 0; i < names.size(); i++) {
            byCategory.put(names.get(i), chart.get("series").get(0).get("data").get(i).decimalValue()
                    .stripTrailingZeros().toPlainString());
        }
        assertEquals(Map.of("Food", "190", "Travel", "70"), byCategory);
        assertEquals(4, chart.get("meta").get("rowCount").asLong());
    }

    // ------------------------------------------------------------------ helpers

    private UUID txn(Account account, String amount, String description, String... categoryNames) {
        User owner = account.getUser();
        Transaction t = new Transaction(account, AppTime.today(), new BigDecimal(amount), description,
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setUser(owner);
        for (String name : categoryNames) {
            t.getCategories().add(new TransactionCategory(t, categories.get(name)));
        }
        return transactionRepository.save(t).getId();
    }

    private static Map<String, Object> filter(String operator, Object value) {
        return Map.of("field", "category", "operator", operator, "value", value);
    }

    private static Map<String, Object> kpi(String aggregation, Map<String, Object> filter) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", "spend");
        def.put("aggregation", aggregation);
        def.put("filters", List.of(filter));
        return Map.of("type", "KPI", "datasource", "transactions", "definition", def);
    }

    private static Map<String, Object> pivot(String row, Map<String, Object> filter) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("mode", "aggregated");
        def.put("rows", List.of(Map.of("field", row)));
        def.put("columns", List.of());
        def.put("measures", List.of(Map.of("field", "spend", "aggregation", "sum")));
        def.put("filters", List.of(filter));
        def.put("sort", List.of());
        return Map.of("type", "TABLE", "datasource", "transactions", "definition", def);
    }

    private static Map<String, Object> chart(String dimension, Map<String, Object> filter) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("chartType", "bar");
        def.put("dimension", Map.of("field", dimension));
        def.put("measure", Map.of("field", "spend", "aggregation", "sum"));
        def.put("filters", List.of(filter));
        return Map.of("type", "CHART", "datasource", "transactions", "definition", def);
    }

    /** Row label -> its spend sum (plain), for a pivot without column dimensions. */
    private static Map<String, String> pivotSums(JsonNode pivot, String field) {
        Map<String, String> sums = new HashMap<>();
        for (JsonNode row : pivot.get("rows")) {
            sums.put(row.get("values").get(field).asText(),
                    row.get("cells").get("").get("spend_sum").decimalValue().stripTrailingZeros().toPlainString());
        }
        return sums;
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static List<UUID> rowIds(JsonNode table) {
        List<UUID> ids = new ArrayList<>();
        table.get("rows").forEach(r -> ids.add(UUID.fromString(r.get("id").asText())));
        return ids;
    }

    private static List<UUID> sorted(List<UUID> ids) {
        return ids.stream().sorted().toList();
    }

    private static BigDecimal sum(JsonNode table, String key) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode row : table.get("rows")) {
            total = total.add(row.get(key).decimalValue());
        }
        return total;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asText()));
        return values;
    }
}
