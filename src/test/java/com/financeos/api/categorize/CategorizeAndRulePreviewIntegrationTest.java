package com.financeos.api.categorize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** POST /categorize (rules only), rule create validation, and preview-matches' matchCount / userSetCount. */
@SpringBootTest
@AutoConfigureMockMvc
class CategorizeAndRulePreviewIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private CategoryRuleRepository ruleRepository;

    private final List<UUID> userIds = new ArrayList<>();
    private ApiTestClient api;
    private UUID account;

    @BeforeEach
    void setUp() throws Exception {
        api = newUser("rule-a");
        account = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank", "last4", "4321",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, userIds);
    }

    private ApiTestClient newUser(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@example.test";
        ApiTestClient client = ApiTestClient.signUp(mockMvc, mapper, email);
        userIds.add(userRepository.findByEmail(email).orElseThrow().getId());
        return client;
    }

    /** An ingested-looking transaction: rules only match sourcedDescription. */
    private UUID ingested(String sourced) throws Exception {
        UUID id = api.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", "2026-10-05",
                "amount", -50, "description", "x"));
        Transaction t = transactionRepository.findById(id).orElseThrow();
        t.setSourcedDescription(sourced);
        transactionRepository.save(t);
        return id;
    }

    private Map<String, Object> rule(String pattern, String type, String category, String sub) {
        Map<String, Object> b = new HashMap<>();
        b.put("merchantKey", pattern);
        b.put("matchType", type);
        b.put("categoryKey", category);
        b.put("subcategoryKey", sub);
        return b;
    }

    @Test
    void categorizeSuggestsFromTheUsersRulesAndIsEmptyOtherwise() throws Exception {
        api.postJson("/api/v1/rules", rule("swiggy", "CONTAINS", "food", "food.delivery"), 201);

        JsonNode hit = api.postJson("/api/v1/categorize", Map.of("description", "UPI SWIGGY ORDER 42"), 200);
        assertEquals("food", hit.get("categoryKey").asText());
        assertEquals("food.delivery", hit.get("subcategoryKey").asText());
        assertEquals("RULE", hit.get("source").asText());

        JsonNode miss = api.postJson("/api/v1/categorize", Map.of("description", "AMAZON PAY"), 200);
        assertTrue(miss.get("categoryKey").isNull());
        assertTrue(miss.get("subcategoryKey").isNull());
        assertTrue(miss.get("source").isNull());
    }

    @Test
    void categorizeNeverUsesAnotherUsersRules() throws Exception {
        api.postJson("/api/v1/rules", rule("swiggy", "CONTAINS", "food", null), 201);
        ApiTestClient other = newUser("rule-b");

        JsonNode res = other.postJson("/api/v1/categorize", Map.of("description", "UPI SWIGGY ORDER 42"), 200);

        assertTrue(res.get("categoryKey").isNull());
    }

    @Test
    void createRuleStoresCategoryAndSubcategoryAndRejectsBadOnes() throws Exception {
        JsonNode created = api.postJson("/api/v1/rules", rule("zomato", "STARTS_WITH", "food", "food.delivery"), 201);
        assertEquals("food", created.get("category").get("key").asText());
        assertEquals("food.delivery", created.get("subcategory").get("key").asText());
        assertEquals("STARTS_WITH", created.get("matchType").asText());
        assertFalse(created.has("verified"));
        assertFalse(created.has("source"));
        assertFalse(created.has("categories"));

        JsonNode defaultType = api.postJson("/api/v1/rules", rule("uber", null, "transport", null), 201);
        assertEquals("CONTAINS", defaultType.get("matchType").asText());

        api.postJson("/api/v1/rules", rule("nope", "CONTAINS", "not_a_category", null), 400);
        api.postJson("/api/v1/rules", rule("nope", "CONTAINS", "food", "travel.flights"), 400);
        api.postJson("/api/v1/rules", rule("nope", "MERCHANT_KEY", "food", null), 400);
        api.postJson("/api/v1/rules", rule("zomato", "STARTS_WITH", "food", null), 409);
        api.postJson("/api/v1/rules", Map.of("merchantKey", "nocat"), 400);
    }

    @Test
    void previewReportsMatchCountAndHowManyTheUserCategorised() throws Exception {
        UUID byUser = ingested("UPI SWIGGY A");
        UUID byRule = ingested("UPI SWIGGY B");
        ingested("UPI SWIGGY C");
        ingested("AMAZON PAY");
        api.putJson("/api/v1/transactions/" + byUser + "/category", Map.of("categoryKey", "shopping"), 200);
        Transaction r = transactionRepository.findById(byRule).orElseThrow();
        r.setCategoryKey("food");
        r.setCategorySource(CategorySource.RULE);
        transactionRepository.save(r);

        JsonNode preview = api.postJson("/api/v1/rules/preview-matches?size=2",
                Map.of("merchantKey", "swiggy", "matchType", "CONTAINS"), 200);

        assertEquals(3, preview.get("matchCount").asInt());
        assertEquals(1, preview.get("userSetCount").asInt());
        assertEquals(2, preview.get("matches").get("content").size());
        assertEquals(3, preview.get("matches").get("totalElements").asInt());
        JsonNode first = preview.get("matches").get("content").get(0);
        assertTrue(first.has("category"));
        assertTrue(first.has("categorySource"));
    }

    @Test
    void previewOnlyCountsTheCallersOwnTransactions() throws Exception {
        ingested("UPI SWIGGY A");
        ApiTestClient other = newUser("rule-b");

        JsonNode preview = other.postJson("/api/v1/rules/preview-matches",
                Map.of("merchantKey", "swiggy", "matchType", "CONTAINS"), 200);

        assertEquals(0, preview.get("matchCount").asInt());
        assertEquals(0, preview.get("userSetCount").asInt());
    }
}
