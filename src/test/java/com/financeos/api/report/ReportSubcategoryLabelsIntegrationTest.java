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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sub-category grouping labels ("Category · Sub", "Category · Others", "Uncategorized") and the filter that
 * resolves each of them back to exactly its rows, plus the filter dropdown's names and order.
 *
 * <p>Fixture (debits today): food/food.delivery 100, food/food.dining 50, food (no sub) 25,
 * groceries (no sub) 10, shopping/shopping.kids 7, family/family.kids 3, uncategorised 20.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportSubcategoryLabelsIntegrationTest {

    private static final String DOT = " · ";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private UUID userId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "sub-labels-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank", "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        Account account = accountRepository.findById(bank).orElseThrow();
        txn(account, "100", "food", "food.delivery");
        txn(account, "50", "food", "food.dining");
        txn(account, "25", "food", null);
        txn(account, "10", "groceries", null);
        txn(account, "7", "shopping", "shopping.kids");
        txn(account, "3", "family", "family.kids");
        txn(account, "20", null, null);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void groupingPrintsCategoryDotSubOrCategoryDotOthersOrUncategorized() throws Exception {
        assertEquals(Map.of("Food" + DOT + "Delivery", "100", "Food" + DOT + "Dining", "50",
                "Food" + DOT + "Others", "25", "Groceries" + DOT + "Others", "10",
                "Shopping" + DOT + "Kids", "7", "Family" + DOT + "Kids", "3", "Uncategorized", "20"),
                chartValues("subcategory"));
    }

    @Test
    void everyBucketLabelDrillsBackToExactlyItsRows() throws Exception {
        Map<String, String> buckets = chartValues("subcategory");
        for (Map.Entry<String, String> bucket : buckets.entrySet()) {
            assertDecimal(bucket.getValue(), api.postJson("/api/v1/reports/data",
                    kpi("subcategory", "is", bucket.getKey()), 200).get("value"));
        }
        JsonNode underlying = api.postJson("/api/v1/reports/underlying", kpi("subcategory", "is", "Food" + DOT + "Others"), 200);
        assertEquals(1, underlying.get("rowCount").asLong());
    }

    @Test
    void othersMatchesOnlyThatCategoryWithoutASubCategory() throws Exception {
        assertDecimal("25", sum("subcategory", "is", "Food" + DOT + "Others"));
        assertDecimal("10", sum("subcategory", "is", "Groceries" + DOT + "Others"));
        assertDecimal("35", sum("subcategory", "in", List.of("Food" + DOT + "Others", "Groceries" + DOT + "Others")));
        assertDecimal("75", sum("subcategory", "in", List.of("Food" + DOT + "Others", "food.dining")));
        // A category whose rows all have a sub-category has no "Others" rows.
        assertDecimal("0", sum("subcategory", "is", "Shopping" + DOT + "Others"));
        assertDecimal("0", sum("subcategory", "is", "Family" + DOT + "Others"));
    }

    @Test
    void negatedOthersKeepsEveryOtherRowIncludingUncategorised() throws Exception {
        assertDecimal("190", sum("subcategory", "is_not", "Food" + DOT + "Others"));
        assertDecimal("180", sum("subcategory", "not_in", List.of("Food" + DOT + "Others", "Groceries" + DOT + "Others")));
    }

    @Test
    void labelsKeysUncategorizedAndTheLegacyNoneAllResolve() throws Exception {
        assertDecimal("7", sum("subcategory", "is", "Shopping" + DOT + "Kids"));
        assertDecimal("3", sum("subcategory", "is", "Family" + DOT + "Kids"));
        assertDecimal("10", sum("subcategory", "in", List.of("Shopping" + DOT + "Kids", "family.kids")));
        assertDecimal("100", sum("subcategory", "is", "food.delivery"));
        assertDecimal("20", sum("subcategory", "is", "Uncategorized"));
        assertDecimal("35", sum("subcategory", "is", "(none)"));
        // The old bare and bracketed names no longer match.
        assertDecimal("0", sum("subcategory", "is", "Kids (Shopping)"));
    }

    @Test
    void theCategoryDimensionIsUnchanged() throws Exception {
        assertEquals(Map.of("Food", "175", "Groceries", "10", "Shopping", "7", "Family", "3", "Uncategorized", "20"),
                chartValues("category"));
    }

    @Test
    void filterValuesListCategoriesInListOrderWithUncategorizedAndSubsNamedByCategoryInTheirOrder() throws Exception {
        JsonNode values = api.getJson("/api/v1/report/datasource/transactions/values", 200);

        List<String> categoryKeys = new ArrayList<>();
        values.get("options").get("category").forEach(o -> categoryKeys.add(o.get("value").asText()));
        assertEquals(List.of("food", "groceries", "shopping", "family", "Uncategorized"), categoryKeys);

        List<String> subLabels = new ArrayList<>();
        List<String> subKeys = new ArrayList<>();
        values.get("options").get("subcategory").forEach(o -> {
            subKeys.add(o.get("value").asText());
            subLabels.add(o.get("label").asText());
        });
        assertEquals(List.of("food.dining", "food.delivery", "shopping.kids", "family.kids"), subKeys);
        assertEquals(List.of("Food" + DOT + "Dining", "Food" + DOT + "Delivery", "Shopping" + DOT + "Kids",
                "Family" + DOT + "Kids"), subLabels);
    }

    // ------------------------------------------------------------------ helpers

    private void txn(Account account, String amount, String category, String sub) {
        Transaction t = new Transaction(account, AppTime.today(), new BigDecimal(amount), "t" + amount,
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setUser(account.getUser());
        t.setCategoryKey(category);
        t.setSubcategoryKey(sub);
        transactionRepository.save(t);
    }

    private JsonNode sum(String field, String operator, Object value) throws Exception {
        return api.postJson("/api/v1/reports/data", kpi(field, operator, value), 200).get("value");
    }

    private static Map<String, Object> kpi(String field, String operator, Object value) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", "spend");
        def.put("aggregation", "sum");
        def.put("filters", List.of(Map.of("field", field, "operator", operator, "value", value)));
        return Map.of("type", "KPI", "datasource", "transactions", "definition", def);
    }

    private Map<String, String> chartValues(String dimension) throws Exception {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("chartType", "bar");
        def.put("dimension", Map.of("field", dimension));
        def.put("measure", Map.of("field", "spend", "aggregation", "sum"));
        def.put("filters", List.of());
        JsonNode chart = api.postJson("/api/v1/reports/data",
                Map.of("type", "CHART", "datasource", "transactions", "definition", def), 200);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < chart.get("categories").size(); i++) {
            out.put(chart.get("categories").get(i).asText(), chart.get("series").get(0).get("data").get(i)
                    .decimalValue().stripTrailingZeros().toPlainString());
        }
        return out;
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }
}
