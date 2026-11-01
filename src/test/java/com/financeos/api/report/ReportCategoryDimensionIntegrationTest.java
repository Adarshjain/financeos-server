package com.financeos.api.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Tag;
import com.financeos.domain.category.TagRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionTag;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fixed-list dimensions of the transactions report: {@code category} / {@code subcategory} group by the
 * stored key and read as labels ("Uncategorized" for none), their filters take keys (a category key
 * includes its sub-categories), and the old per-user categories live on as the {@code tag} dimension.
 *
 * <p>Fixture (debits today, user A): food/food.delivery 100, food/food.dining 50, food (no sub) 25,
 * groceries 10, shopping/shopping.kids 7, family/family.kids 3, uncategorised 20 (tagged Work).
 * User B has a 999 food debit that must never show up in A's numbers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportCategoryDimensionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TagRepository tagRepository;

    private ApiTestClient api;
    private UUID userA;
    private UUID userB;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = UUID.randomUUID().toString();
        api = ApiTestClient.signUp(mockMvc, mapper, "cat-dim-a-" + suffix + "@example.test");
        userA = userRepository.findByEmail("cat-dim-a-" + suffix + "@example.test").orElseThrow().getId();
        Account bankA = bank(api, "Bank A");
        ApiTestClient apiB = ApiTestClient.signUp(mockMvc, mapper, "cat-dim-b-" + suffix + "@example.test");
        userB = userRepository.findByEmail("cat-dim-b-" + suffix + "@example.test").orElseThrow().getId();
        Account bankB = bank(apiB, "Bank B");

        Tag work = tagRepository.save(new Tag("Work", bankA.getUser()));
        txn(bankA, "100", "food", "food.delivery", null);
        txn(bankA, "50", "food", "food.dining", null);
        txn(bankA, "25", "food", null, null);
        txn(bankA, "10", "groceries", null, null);
        txn(bankA, "7", "shopping", "shopping.kids", null);
        txn(bankA, "3", "family", "family.kids", null);
        txn(bankA, "20", null, null, work);
        txn(bankB, "999", "food", "food.delivery", null);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userA, userB));
    }

    @Test
    void categoryDimensionGroupsByCategoryWithLabelsAndAnUncategorizedBucket() throws Exception {
        Map<String, String> byCategory = chartValues(chart("category", List.of()));

        assertEquals(Map.of("Food", "175", "Groceries", "10", "Shopping", "7", "Family", "3", "Uncategorized", "20"),
                byCategory);
    }

    @Test
    void subcategoryDimensionGroupsBySubWithLabelsQualifiedWhereTwoCategoriesShareAName() throws Exception {
        Map<String, String> bySub = chartValues(chart("subcategory", List.of()));

        assertEquals(Map.of("Delivery", "100", "Dining", "50", "(none)", "35",
                "Kids (Shopping)", "7", "Kids (Family)", "3", "Uncategorized", "20"), bySub);
    }

    @Test
    void aCategoryKeyFilterIncludesItsSubCategoriesAndNothingElse() throws Exception {
        JsonNode in = api.postJson("/api/v1/reports/data", kpi(filter("category", "is", "food")), 200);
        JsonNode inMany = api.postJson("/api/v1/reports/data", kpi(filter("category", "in", List.of("food", "groceries"))), 200);
        JsonNode isNot = api.postJson("/api/v1/reports/data", kpi(filter("category", "is_not", "food")), 200);

        assertDecimal("175", in.get("value"));
        assertDecimal("185", inMany.get("value"));
        // Uncategorised rows pass a negated filter, like any enum.
        assertDecimal("40", isNot.get("value"));
    }

    @Test
    void aSubCategoryKeyFilterMatchesOnlyThatSubCategory() throws Exception {
        JsonNode kids = api.postJson("/api/v1/reports/data", kpi(filter("subcategory", "is", "family.kids")), 200);
        JsonNode both = api.postJson("/api/v1/reports/data",
                kpi(filter("subcategory", "in", List.of("shopping.kids", "family.kids"))), 200);

        assertDecimal("3", kids.get("value"));
        assertDecimal("10", both.get("value"));
    }

    @Test
    void aDrillOnACategoryLabelFindsItsRowsLikeTheKey() throws Exception {
        assertDecimal("175", api.postJson("/api/v1/reports/data", kpi(filter("category", "is", "Food")), 200).get("value"));
        assertDecimal("185", api.postJson("/api/v1/reports/data",
                kpi(filter("category", "in", List.of("Food", "groceries"))), 200).get("value"));
        assertDecimal("40", api.postJson("/api/v1/reports/data", kpi(filter("category", "is_not", "Food")), 200).get("value"));
        JsonNode underlying = api.postJson("/api/v1/reports/underlying", kpi(filter("category", "is", "Food")), 200);
        assertEquals(3, underlying.get("rowCount").asLong());
    }

    @Test
    void theUncategorizedBucketDrillsToRowsWithNoCategory() throws Exception {
        assertDecimal("20", api.postJson("/api/v1/reports/data", kpi(filter("category", "is", "Uncategorized")), 200).get("value"));
        assertDecimal("20", api.postJson("/api/v1/reports/data", kpi(filter("subcategory", "is", "Uncategorized")), 200).get("value"));
        assertDecimal("195", api.postJson("/api/v1/reports/data", kpi(filter("category", "is_not", "Uncategorized")), 200).get("value"));
        assertDecimal("33", api.postJson("/api/v1/reports/data",
                kpi(filter("category", "in", List.of("Uncategorized", "Family", "groceries"))), 200).get("value"));
    }

    @Test
    void theNoneSubCategoryBucketDrillsToACategorisedRowWithoutASub() throws Exception {
        assertDecimal("35", api.postJson("/api/v1/reports/data", kpi(filter("subcategory", "is", "(none)")), 200).get("value"));
        assertDecimal("180", api.postJson("/api/v1/reports/data", kpi(filter("subcategory", "is_not", "(none)")), 200).get("value"));
    }

    @Test
    void aQualifiedSubCategoryLabelAndAPlainOneDrillToTheRightRows() throws Exception {
        assertDecimal("7", api.postJson("/api/v1/reports/data", kpi(filter("subcategory", "is", "Kids (Shopping)")), 200).get("value"));
        assertDecimal("3", api.postJson("/api/v1/reports/data", kpi(filter("subcategory", "is", "Kids (Family)")), 200).get("value"));
        assertDecimal("100", api.postJson("/api/v1/reports/data", kpi(filter("subcategory", "is", "Delivery")), 200).get("value"));
        assertDecimal("107", api.postJson("/api/v1/reports/data",
                kpi(filter("subcategory", "in", List.of("Delivery", "shopping.kids"))), 200).get("value"));
        // Another user's food row never leaks into a label drill.
        assertDecimal("175", api.postJson("/api/v1/reports/data", kpi(filter("category", "is", "Food")), 200).get("value"));
    }

    @Test
    void filteringACategoryAndGroupingByItNarrowsTheGroups() throws Exception {
        Map<String, String> grouped = chartValues(chart("category", List.of(filter("category", "in", List.of("food", "family")))));

        assertEquals(Map.of("Food", "175", "Family", "3"), grouped);
    }

    @Test
    void tagDimensionKeepsTheOldBehaviourOverTransactionTags() throws Exception {
        Map<String, String> byTag = chartValues(chart("tag", List.of()));
        JsonNode filtered = api.postJson("/api/v1/reports/data", kpi(filter("tag", "is", "Work")), 200);

        assertEquals(Map.of("Work", "20", "(none)", "195"), byTag);
        assertDecimal("20", filtered.get("value"));
    }

    @Test
    void rawTableShowsPlainCategoryLabelsAndEmptyWhenNone() throws Exception {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("mode", "raw");
        def.put("columns", List.of("spend", "category", "subcategory", "tag"));
        def.put("filters", List.of());
        def.put("sort", List.of(Map.of("key", "spend", "direction", "desc")));
        JsonNode table = api.postJson("/api/v1/reports/data",
                Map.of("type", "TABLE", "datasource", "transactions", "definition", def), 200);

        assertEquals(7, table.get("rows").size(), "only the caller's transactions");
        JsonNode top = table.get("rows").get(0);
        assertEquals("Food", top.get("category").asText());
        assertEquals("Delivery", top.get("subcategory").asText());
        JsonNode uncategorised = table.get("rows").get(3);
        assertEquals("Work", uncategorised.get("tag").asText());
        assertTrue(uncategorised.get("category").isNull() || uncategorised.get("category").asText().isEmpty());
    }

    @Test
    void theCatalogOffersTagCategoryAndSubcategoryDimensions() throws Exception {
        JsonNode values = api.getJson("/api/v1/report/datasource/transactions/values", 200);

        JsonNode categories = values.get("options").get("category");
        Map<String, String> byKey = new HashMap<>();
        categories.forEach(o -> byKey.put(o.get("value").asText(), o.get("label").asText()));
        assertEquals("Food", byKey.get("food"));
        assertEquals("Groceries", byKey.get("groceries"));
        assertTrue(!byKey.containsKey("999"), "no other user's values");
        assertEquals(List.of("Work"), texts(values.get("values").get("tag")));
    }

    // ------------------------------------------------------------------ helpers

    private Account bank(ApiTestClient client, String name) throws Exception {
        UUID id = client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        return accountRepository.findById(id).orElseThrow();
    }

    private void txn(Account account, String amount, String category, String sub, Tag tag) {
        Transaction t = new Transaction(account, AppTime.today(), new BigDecimal(amount), "t" + amount,
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setUser(account.getUser());
        t.setCategoryKey(category);
        t.setSubcategoryKey(sub);
        if (tag != null) {
            t.getTags().add(new TransactionTag(t, tag));
        }
        transactionRepository.save(t);
    }

    private static Map<String, Object> filter(String field, String operator, Object value) {
        return Map.of("field", field, "operator", operator, "value", value);
    }

    private static Map<String, Object> kpi(Map<String, Object> filter) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("measure", "spend");
        def.put("aggregation", "sum");
        def.put("filters", List.of(filter));
        return Map.of("type", "KPI", "datasource", "transactions", "definition", def);
    }

    private static Map<String, Object> chart(String dimension, List<Map<String, Object>> filters) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("chartType", "bar");
        def.put("dimension", Map.of("field", dimension));
        def.put("measure", Map.of("field", "spend", "aggregation", "sum"));
        def.put("filters", filters);
        return Map.of("type", "CHART", "datasource", "transactions", "definition", def);
    }

    private Map<String, String> chartValues(Map<String, Object> request) throws Exception {
        JsonNode chart = api.postJson("/api/v1/reports/data", request, 200);
        Map<String, String> out = new HashMap<>();
        List<String> names = texts(chart.get("categories"));
        for (int i = 0; i < names.size(); i++) {
            out.put(names.get(i), chart.get("series").get(0).get("data").get(i).decimalValue()
                    .stripTrailingZeros().toPlainString());
        }
        return out;
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static List<String> texts(JsonNode array) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
