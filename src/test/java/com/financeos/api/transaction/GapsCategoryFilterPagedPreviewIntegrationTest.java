package com.financeos.api.transaction;

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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transactions "no category" filter, paged category search, and the account on rule match previews. */
@SpringBootTest
@AutoConfigureMockMvc
class GapsCategoryFilterPagedPreviewIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private final LocalDate today = AppTime.today();
    private ApiTestClient api;
    private UUID userId;
    private UUID bank;
    private UUID otherBank;

    @BeforeEach
    void setUp() throws Exception {
        String email = "gaps-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main Bank", "last4", "1111",
                "bank", "hdfc", "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        otherBank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Side Bank", "last4", "2222",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    private UUID category(String name) throws Exception {
        return api.create("/api/v1/categories", Map.of("name", name));
    }

    private UUID txn(UUID account, int amount, String description, List<UUID> categories) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", today.minusDays(1).toString());
        body.put("amount", amount);
        body.put("description", description);
        body.put("categoryIds", categories.stream().map(UUID::toString).toList());
        return api.create("/api/v1/transactions", body);
    }

    private JsonNode search(List<Map<String, Object>> filters) throws Exception {
        return api.postJson("/api/v1/transactions/search", Map.of("filters", filters), 200);
    }

    private static Map<String, Object> isEmpty() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", "category");
        f.put("operator", "is_empty");
        f.put("value", null);
        return f;
    }

    private static List<String> descriptions(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("description").asText()));
        return out;
    }

    // ------------------------------------------------------------------ 1. is_empty

    @Test
    void isEmptyListsAndCountsOnlyUncategorisedTransactions() throws Exception {
        UUID food = category("Food");
        txn(bank, -10, "has category", List.of(food));
        txn(bank, -20, "bare one", List.of());
        txn(otherBank, -30, "bare two", List.of());

        JsonNode page = search(List.of(isEmpty()));

        assertEquals(2, page.get("totalElements").asLong());
        assertEquals(List.of("bare one", "bare two").stream().sorted().toList(),
                descriptions(page).stream().sorted().toList());
    }

    @Test
    void isEmptyCombinesWithOtherFilters() throws Exception {
        UUID food = category("Food");
        txn(bank, -10, "has category", List.of(food));
        txn(bank, -20, "bare one", List.of());
        txn(otherBank, -30, "bare two", List.of());

        JsonNode page = search(List.of(isEmpty(),
                Map.of("field", "accountId", "operator", "is", "value", otherBank.toString())));

        assertEquals(1, page.get("totalElements").asLong());
        assertEquals(List.of("bare two"), descriptions(page));
    }

    @Test
    void isEmptyAcceptsAnExplicitJsonNullAndRejectsAValue() throws Exception {
        txn(bank, -20, "bare one", List.of());
        String ok = "{\"filters\":[{\"field\":\"category\",\"operator\":\"is_empty\",\"value\":null}]}";
        assertEquals(1, api.postJson("/api/v1/transactions/search", mapper.readTree(ok), 200).get("totalElements").asLong());
        String noValueKey = "{\"filters\":[{\"field\":\"category\",\"operator\":\"is_empty\"}]}";
        assertEquals(1, api.postJson("/api/v1/transactions/search", mapper.readTree(noValueKey), 200).get("totalElements").asLong());

        Map<String, Object> withValue = Map.of("filters", List.of(
                Map.of("field", "category", "operator", "is_empty", "value", "Food")));
        assertEquals(400, api.post("/api/v1/transactions/search", withValue).getStatus());
    }

    @Test
    void isEmptyIsOnlyValidForCategory() throws Exception {
        Map<String, Object> body = Map.of("filters", List.of(Map.of("field", "type", "operator", "is_empty")));
        assertEquals(400, api.post("/api/v1/transactions/search", body).getStatus());
    }

    // ------------------------------------------------------------------ 2. paged categories

    @Test
    void categoriesPageIsSortedByNameCaseInsensitivelyAndPaged() throws Exception {
        category("banana");
        category("Apple");
        category("cherry");
        category("Date");

        JsonNode first = api.getJson("/api/v1/categories/page?size=3&page=0", 200);
        JsonNode second = api.getJson("/api/v1/categories/page?size=3&page=1", 200);

        assertEquals(4, first.get("totalElements").asLong());
        assertEquals(2, first.get("totalPages").asInt());
        List<String> names = new ArrayList<>();
        first.get("content").forEach(n -> names.add(n.get("name").asText()));
        second.get("content").forEach(n -> names.add(n.get("name").asText()));
        assertEquals(List.of("Apple", "banana", "cherry", "Date"), names);
        assertTrue(first.get("content").get(0).has("id"));
    }

    @Test
    void categoriesPageSearchMatchesNameContainsIgnoringCaseAndLikeWildcards() throws Exception {
        category("Groceries");
        category("Dining out");
        category("a_b");
        category("axb");

        JsonNode hit = api.getJson("/api/v1/categories/page?search=GRO", 200);
        assertEquals(1, hit.get("totalElements").asLong());
        assertEquals("Groceries", hit.get("content").get(0).get("name").asText());

        assertEquals(1, api.getJson("/api/v1/categories/page?search=a_b", 200).get("totalElements").asLong(),
                "an underscore is literal, not a LIKE wildcard");
        assertEquals(0, api.getJson("/api/v1/categories/page?search=zzz", 200).get("totalElements").asLong());
        assertEquals(4, api.getJson("/api/v1/categories/page?search=", 200).get("totalElements").asLong());
    }

    @Test
    void unpagedCategoryListIsUnchangedAndOtherUsersCategoriesAreNotInThePage() throws Exception {
        category("Mine");
        JsonNode list = api.getJson("/api/v1/categories", 200);
        assertTrue(list.isArray());
        assertEquals(1, list.size());

        String otherEmail = "gaps-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        UUID otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        try {
            other.create("/api/v1/categories", Map.of("name", "Theirs"));
            assertEquals(1, api.getJson("/api/v1/categories/page", 200).get("totalElements").asLong());
        } finally {
            UserDataCleanup.deleteUsers(jdbc, List.of(otherId));
        }
    }

    // ------------------------------------------------------------------ 3. rule preview account

    @Test
    void previewMatchesReturnsEachMatchesAccount() throws Exception {
        UUID a = txn(bank, -10, "swiggy one", List.of());
        UUID b = txn(otherBank, -20, "swiggy two", List.of());
        txn(bank, -30, "unrelated", List.of());
        jdbc.update("UPDATE transactions SET sourced_description = description WHERE user_id = ?", userId.toString());

        JsonNode page = api.postJson("/api/v1/rules/preview-matches",
                Map.of("merchantKey", "SWIGGY", "matchType", "CONTAINS"), 200);

        assertEquals(2, page.get("totalElements").asLong());
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        page.get("content").forEach(n -> byId.put(n.get("id").asText(), n));
        JsonNode one = byId.get(a.toString());
        assertEquals(bank.toString(), one.get("accountId").asText());
        assertEquals("Main Bank", one.get("accountName").asText());
        assertEquals("hdfc", one.get("accountBank").asText());
        JsonNode two = byId.get(b.toString());
        assertEquals(otherBank.toString(), two.get("accountId").asText());
        assertEquals("Side Bank", two.get("accountName").asText());
        assertTrue(two.get("accountBank").isNull());
        assertTrue(two.get("cardId").isNull());
    }
}
