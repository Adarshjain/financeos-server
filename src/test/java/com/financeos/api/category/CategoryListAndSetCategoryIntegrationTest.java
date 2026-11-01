package com.financeos.api.category;

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** GET /category-list and PUT /transactions/{id}/category (set, Undo through restore, clear, tenancy). */
@SpringBootTest
@AutoConfigureMockMvc
class CategoryListAndSetCategoryIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private final List<UUID> userIds = new ArrayList<>();
    private ApiTestClient api;
    private UUID account;

    @BeforeEach
    void setUp() throws Exception {
        api = newUser("cat-a");
        account = bank(api);
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

    private static UUID bank(ApiTestClient client) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank", "last4", "4321",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID txn(ApiTestClient client, UUID acct, int amount) throws Exception {
        return client.create("/api/v1/transactions", Map.of("accountId", acct.toString(), "date", "2026-10-05",
                "amount", amount, "description", "something"));
    }

    private static Map<String, Object> body(String key, String sub) {
        Map<String, Object> b = new HashMap<>();
        b.put("categoryKey", key);
        b.put("subcategoryKey", sub);
        return b;
    }

    // ------------------------------------------------------------------ category-list

    @Test
    void categoryListIsTheWholeFixedListWithSubsIconsOrdersAndDirections() throws Exception {
        MockHttpServletResponse response = api.get("/api/v1/category-list");
        assertEquals(200, response.getStatus());
        assertTrue(response.getHeader("Cache-Control").contains("max-age"), "static, so cacheable");

        JsonNode categories = mapper.readTree(response.getContentAsString()).get("categories");
        assertEquals(39, categories.size());
        int subs = 0;
        for (JsonNode c : categories) {
            subs += c.get("subs").size();
        }
        assertEquals(142, subs);

        JsonNode food = categories.get(0);
        assertEquals("food", food.get("key").asText());
        assertEquals("Food", food.get("name").asText());
        assertEquals("OUT", food.get("direction").asText());
        assertEquals(1, food.get("outOrder").asInt());
        assertTrue(food.get("inOrder").isNull());
        assertEquals("cat-fork-knife", food.get("icon").asText());
        assertFalse(food.get("excludedByDefault").asBoolean());
        assertEquals("food.dining", food.get("subs").get(0).get("key").asText());
        assertEquals("Dining", food.get("subs").get(0).get("name").asText());
        assertEquals("OUT", food.get("subs").get(0).get("direction").asText());

        JsonNode selfTransfer = null;
        for (JsonNode c : categories) {
            if (c.get("key").asText().equals("self_transfer")) {
                selfTransfer = c;
            }
        }
        assertNotNull(selfTransfer);
        assertEquals("BOTH", selfTransfer.get("direction").asText());
        assertTrue(selfTransfer.get("excludedByDefault").asBoolean());
    }

    @Test
    void categoryListNeedsASession() throws Exception {
        int status = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/category-list")).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "was " + status);
    }

    @Test
    void theOldCategoriesEndpointsAreGone() throws Exception {
        assertEquals(404, api.get("/api/v1/categories").getStatus());
        assertEquals(404, api.post("/api/v1/categories", Map.of("name", "Food")).getStatus());
    }

    // ------------------------------------------------------------------ PUT /transactions/{id}/category

    @Test
    void setCategoryStoresTheUsersChoiceAndReturnsThePreviousState() throws Exception {
        UUID id = txn(api, account, -250);

        JsonNode first = api.putJson("/api/v1/transactions/" + id + "/category", body("food", "food.dining"), 200);
        assertEquals("food", first.get("category").get("key").asText());
        assertEquals("Food", first.get("category").get("name").asText());
        assertEquals("cat-fork-knife", first.get("category").get("icon").asText());
        assertEquals("food.dining", first.get("subcategory").get("key").asText());
        assertEquals("USER", first.get("categorySource").asText());
        assertFalse(first.get("categorySetAt").isNull());
        assertTrue(first.get("previous").get("categoryKey").isNull());
        assertTrue(first.get("previous").get("source").isNull());

        JsonNode second = api.putJson("/api/v1/transactions/" + id + "/category", body("groceries", null), 200);
        assertEquals("groceries", second.get("category").get("key").asText());
        assertTrue(second.get("subcategory").isNull());
        assertEquals("food", second.get("previous").get("categoryKey").asText());
        assertEquals("food.dining", second.get("previous").get("subcategoryKey").asText());
        assertEquals("USER", second.get("previous").get("source").asText());
        assertEquals(first.get("categorySetAt").asText(), second.get("previous").get("setAt").asText());

        JsonNode read = api.getJson("/api/v1/transactions/" + id, 200);
        assertEquals("groceries", read.get("category").get("key").asText());
        assertEquals("USER", read.get("categorySource").asText());
        assertTrue(read.get("categoryRule").isNull());
    }

    @Test
    void undoRestoresThePreviousStateThroughRestore() throws Exception {
        UUID id = txn(api, account, -250);
        api.putJson("/api/v1/transactions/" + id + "/category", body("food", "food.dining"), 200);
        JsonNode changed = api.putJson("/api/v1/transactions/" + id + "/category", body("groceries", null), 200);
        JsonNode prev = changed.get("previous");

        Map<String, Object> restore = new HashMap<>();
        restore.put("source", prev.get("source").asText());
        restore.put("ruleId", null);
        restore.put("setAt", prev.get("setAt").asText());
        Map<String, Object> undo = body(prev.get("categoryKey").asText(), prev.get("subcategoryKey").asText());
        undo.put("restore", restore);
        JsonNode undone = api.putJson("/api/v1/transactions/" + id + "/category", undo, 200);

        assertEquals("food", undone.get("category").get("key").asText());
        assertEquals("food.dining", undone.get("subcategory").get("key").asText());
        assertEquals("USER", undone.get("categorySource").asText());
        assertEquals(prev.get("setAt").asText(), undone.get("categorySetAt").asText());
    }

    @Test
    void undoOfTheFirstSetPutsBackNoCategoryAtAll() throws Exception {
        UUID id = txn(api, account, -250);
        JsonNode first = api.putJson("/api/v1/transactions/" + id + "/category", body("food", null), 200);
        assertTrue(first.get("previous").get("source").isNull());

        Map<String, Object> restore = new HashMap<>();
        restore.put("source", null);
        restore.put("ruleId", null);
        restore.put("setAt", null);
        Map<String, Object> undo = body(null, null);
        undo.put("restore", restore);
        JsonNode undone = api.putJson("/api/v1/transactions/" + id + "/category", undo, 200);

        assertTrue(undone.get("category").isNull());
        assertTrue(undone.get("categorySource").isNull());
        assertTrue(undone.get("categorySetAt").isNull());
    }

    @Test
    void nullKeysClearTheCategoryAsTheUser() throws Exception {
        UUID id = txn(api, account, -250);
        api.putJson("/api/v1/transactions/" + id + "/category", body("food", null), 200);

        JsonNode cleared = api.putJson("/api/v1/transactions/" + id + "/category", body(null, null), 200);

        assertTrue(cleared.get("category").isNull());
        assertEquals("USER", cleared.get("categorySource").asText());
    }

    @Test
    void aCategoryThatDoesNotFitTheDirectionOrExistIs400() throws Exception {
        UUID debit = txn(api, account, -250);
        UUID credit = txn(api, account, 250);

        api.putJson("/api/v1/transactions/" + debit + "/category", body("salary", null), 400);
        api.putJson("/api/v1/transactions/" + credit + "/category", body("food", null), 400);
        api.putJson("/api/v1/transactions/" + debit + "/category", body("nope", null), 400);
        api.putJson("/api/v1/transactions/" + debit + "/category", body("food", "travel.flights"), 400);
        assertTrue(api.getJson("/api/v1/transactions/" + debit, 200).get("category").isNull());
    }

    @Test
    void anotherUsersTransactionIs404() throws Exception {
        UUID mine = txn(api, account, -250);
        ApiTestClient other = newUser("cat-b");

        other.putJson("/api/v1/transactions/" + mine + "/category", body("food", null), 404);
        assertTrue(api.getJson("/api/v1/transactions/" + mine, 200).get("category").isNull());
    }

    @Test
    void settingAnExcludedByDefaultCategoryExcludesUnlessTheUserChoseIt() throws Exception {
        UUID id = txn(api, account, -250);

        JsonNode set = api.putJson("/api/v1/transactions/" + id + "/category", body("self_transfer", null), 200);
        assertTrue(set.get("isTransactionExcluded").asBoolean());

        JsonNode back = api.putJson("/api/v1/transactions/" + id + "/category", body("food", null), 200);
        assertFalse(back.get("isTransactionExcluded").asBoolean());
    }

    @Test
    void creatingWithACategoryAndUpdatingWithoutOneKeepsIt() throws Exception {
        UUID id = api.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", "2026-10-05",
                "amount", -90, "description", "Lunch", "categoryKey", "food", "subcategoryKey", "food.dining"));
        JsonNode created = api.getJson("/api/v1/transactions/" + id, 200);
        assertEquals("food", created.get("category").get("key").asText());
        assertEquals("USER", created.get("categorySource").asText());

        api.putJson("/api/v1/transactions/" + id, Map.of("date", "2026-10-05", "amount", -95, "description", "Lunch out"), 200);

        JsonNode after = api.getJson("/api/v1/transactions/" + id, 200);
        assertEquals("food", after.get("category").get("key").asText());
        assertEquals("food.dining", after.get("subcategory").get("key").asText());
        assertEquals("Lunch out", after.get("description").asText());
    }
}
