package com.financeos.api.transaction;

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
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * POST /transactions/count equals the search total for the same body, and GET /shell/counts equals each part's own
 * endpoint, for the signed-in user only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionCountAndShellCountsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private final List<UUID> userIds = new ArrayList<>();
    private ApiTestClient api;
    private UUID account;

    @BeforeEach
    void setUp() throws Exception {
        api = newUser("count-a");
        account = bank(api, "Count Bank");
        UUID t1 = txn(api, account, "2026-01-05", -100, "coffee shop");
        UUID t2 = txn(api, account, "2026-02-06", -50, "grocery store");
        txn(api, account, "2026-03-07", 500, "salary");
        txn(api, account, "2026-03-08", -20, "coffee again");
        api.postJson("/api/v1/transactions/batch-review", Map.of("transactionIds", List.of(t1, t2),
                "reviewType", "NEEDS_REVIEW", "reviewReasons", List.of("CATEGORY_UNVERIFIED")), 200);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, userIds);
    }

    @Test
    void countEqualsSearchTotalForSeveralFilterCombinations() throws Exception {
        List<Map<String, Object>> bodies = List.of(
                Map.of(),
                Map.of("search", "coffee"),
                Map.of("filters", List.of(Map.of("field", "reviewType", "operator", "is", "value", "NEEDS_REVIEW"))),
                Map.of("filters", List.of(Map.of("field", "type", "operator", "is", "value", "DEBIT")),
                        "search", "coffee"),
                Map.of("filters", List.of(Map.of("field", "type", "operator", "is", "value", "DEBIT"),
                        Map.of("field", "reviewType", "operator", "is", "value", "NEEDS_REVIEW"))),
                Map.of("filters", List.of(Map.of("field", "description", "operator", "contains", "value", "nomatch"))));
        for (Map<String, Object> body : bodies) {
            JsonNode search = api.postJson("/api/v1/transactions/search?page=0&size=1", body, 200);
            JsonNode count = api.postJson("/api/v1/transactions/count", body, 200);
            assertEquals(search.get("totalElements").asLong(), count.get("count").asLong(), body.toString());
        }
        assertEquals(4, api.postJson("/api/v1/transactions/count", Map.of(), 200).get("count").asLong());
        assertEquals(2, api.postJson("/api/v1/transactions/count", Map.of("search", "coffee"), 200).get("count").asLong());
    }

    @Test
    void countRejectsAnInvalidFilterLikeSearch() throws Exception {
        Map<String, Object> bad = Map.of("filters", List.of(Map.of("field", "nope", "operator", "is", "value", "x")));
        assertEquals(api.post("/api/v1/transactions/search", bad).getStatus(),
                api.post("/api/v1/transactions/count", bad).getStatus());
    }

    @Test
    void countIsScopedToTheCurrentUser() throws Exception {
        ApiTestClient other = newUser("count-b");
        assertEquals(0, other.postJson("/api/v1/transactions/count", Map.of(), 200).get("count").asLong());
    }

    @Test
    void shellCountsEqualEachEndpoint() throws Exception {
        JsonNode shell = api.getJson("/api/v1/shell/counts", 200);
        assertEquals(api.getJson("/api/v1/inbox/summary", 200), shell.get("inbox"));
        assertEquals(api.getJson("/api/v1/notifications/feed/summary", 200).get("unread").asLong(),
                shell.get("notificationsUnread").asLong());
        assertEquals(api.getJson("/api/v1/app-lock/status", 200), shell.get("appLock"));
        JsonNode review = api.postJson("/api/v1/transactions/search?page=0&size=1", Map.of("filters",
                List.of(Map.of("field", "reviewType", "operator", "is", "value", "NEEDS_REVIEW"))), 200);
        assertEquals(review.get("totalElements").asLong(), shell.get("needsReview").asLong());
        assertEquals(2, shell.get("needsReview").asLong());
    }

    @Test
    void shellCountsAreScopedToTheCurrentUser() throws Exception {
        ApiTestClient other = newUser("count-c");
        JsonNode shell = other.getJson("/api/v1/shell/counts", 200);
        assertEquals(0, shell.get("needsReview").asLong());
        assertEquals(0, shell.get("notificationsUnread").asLong());
        assertTrue(shell.get("inbox").get("badge").asInt() >= 0);
    }

    @Test
    void authMeStillReportsTheUser() throws Exception {
        JsonNode me = api.getJson("/api/v1/auth/me", 200);
        assertTrue(me.get("email").asText().startsWith("count-a-"));
    }

    private ApiTestClient newUser(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID() + "@example.test";
        ApiTestClient client = ApiTestClient.signUp(mockMvc, mapper, email);
        userIds.add(userRepository.findByEmail(email).orElseThrow().getId());
        return client;
    }

    private static UUID bank(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", "4321",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private static UUID txn(ApiTestClient client, UUID account, String date, int amount, String description)
            throws Exception {
        return client.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", date,
                "amount", amount, "description", description));
    }
}
