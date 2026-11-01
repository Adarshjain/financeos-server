package com.financeos.api.tag;

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

import static org.junit.jupiter.api.Assertions.*;

/** /tags: CRUD with a transaction count per tag, bulk-add (idempotent, user-scoped), and tags on transactions. */
@SpringBootTest
@AutoConfigureMockMvc
class TagEndpointsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private final List<UUID> userIds = new ArrayList<>();
    private ApiTestClient api;
    private UUID account;

    @BeforeEach
    void setUp() throws Exception {
        api = newUser("tag-a");
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

    private UUID txn(ApiTestClient client, UUID acct) throws Exception {
        return client.create("/api/v1/transactions", Map.of("accountId", acct.toString(), "date", "2026-10-05",
                "amount", -50, "description", "something"));
    }

    private UUID tag(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/tags", Map.of("name", name));
    }

    private int linkCount(UUID transactionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM transaction_tags WHERE transaction_id = ?", Integer.class,
                transactionId.toString());
    }

    private JsonNode byName(JsonNode list, String name) {
        for (JsonNode n : list) {
            if (n.get("name").asText().equals(name)) {
                return n;
            }
        }
        return null;
    }

    @Test
    void createRenameGetDeleteAndTheCountOnEachTag() throws Exception {
        UUID trip = tag(api, "Trip");
        UUID gift = tag(api, "Gift");
        UUID t1 = txn(api, account);
        UUID t2 = txn(api, account);
        api.postJson("/api/v1/tags/bulk-add", Map.of("transactionIds", List.of(t1, t2), "tagIds", List.of(trip)), 200);

        JsonNode list = api.getJson("/api/v1/tags", 200);
        assertEquals(2, byName(list, "Trip").get("transactionCount").asInt());
        assertEquals(0, byName(list, "Gift").get("transactionCount").asInt());
        assertEquals("Gift", list.get(0).get("name").asText(), "name-sorted");

        JsonNode renamed = api.putJson("/api/v1/tags/" + trip, Map.of("name", "Holiday"), 200);
        assertEquals("Holiday", renamed.get("name").asText());
        assertEquals(2, renamed.get("transactionCount").asInt());
        assertEquals(2, api.getJson("/api/v1/tags/" + trip, 200).get("transactionCount").asInt());
        api.putJson("/api/v1/tags/" + gift, Map.of("name", "Holiday"), 409);

        api.deleteJson("/api/v1/tags/" + gift, 204);
        assertEquals(404, api.get("/api/v1/tags/" + gift).getStatus());
    }

    @Test
    void pageIsSearchableAndCarriesCounts() throws Exception {
        UUID trip = tag(api, "Trip");
        tag(api, "Gift");
        UUID t1 = txn(api, account);
        api.postJson("/api/v1/tags/bulk-add", Map.of("transactionIds", List.of(t1), "tagIds", List.of(trip)), 200);

        JsonNode page = api.getJson("/api/v1/tags/page?size=1", 200);
        assertEquals(2, page.get("totalElements").asInt());
        assertEquals(1, page.get("content").size());
        assertEquals("Gift", page.get("content").get(0).get("name").asText());

        JsonNode second = api.getJson("/api/v1/tags/page?size=1&page=1", 200);
        assertEquals("Trip", second.get("content").get(0).get("name").asText());
        assertEquals(1, second.get("content").get(0).get("transactionCount").asInt());
    }

    @Test
    void bulkAddIsIdempotent() throws Exception {
        UUID a = tag(api, "A");
        UUID b = tag(api, "B");
        UUID t1 = txn(api, account);
        UUID t2 = txn(api, account);

        JsonNode first = api.postJson("/api/v1/tags/bulk-add",
                Map.of("transactionIds", List.of(t1, t2), "tagIds", List.of(a, b)), 200);
        assertEquals(2, first.get("transactionsFound").asInt());
        assertEquals(4, first.get("tagsAdded").asInt());

        JsonNode again = api.postJson("/api/v1/tags/bulk-add",
                Map.of("transactionIds", List.of(t1, t2, t1), "tagIds", List.of(a, b)), 200);
        assertEquals(0, again.get("tagsAdded").asInt());
        assertEquals(2, linkCount(t1));
        assertEquals(2, linkCount(t2));

        JsonNode partial = api.postJson("/api/v1/tags/bulk-add",
                Map.of("transactionIds", List.of(t1), "tagIds", List.of(a)), 200);
        assertEquals(0, partial.get("tagsAdded").asInt());
    }

    @Test
    void userBCannotTagUserAsTransaction() throws Exception {
        UUID aTxn = txn(api, account);
        ApiTestClient other = newUser("tag-b");
        UUID bTag = tag(other, "Mine");

        JsonNode res = other.postJson("/api/v1/tags/bulk-add",
                Map.of("transactionIds", List.of(aTxn), "tagIds", List.of(bTag)), 200);

        assertEquals(0, res.get("transactionsFound").asInt());
        assertEquals(0, res.get("tagsAdded").asInt());
        assertEquals(0, linkCount(aTxn), "A's transaction is untouched");
        assertEquals(0, api.getJson("/api/v1/transactions/" + aTxn, 200).get("tags").size());
    }

    @Test
    void userBCannotUseUserAsTagOrSeeItsCount() throws Exception {
        UUID aTag = tag(api, "Private");
        ApiTestClient other = newUser("tag-b");
        UUID bTxn = txn(other, bank(other));

        other.postJson("/api/v1/tags/bulk-add", Map.of("transactionIds", List.of(bTxn), "tagIds", List.of(aTag)), 404);
        assertEquals(0, linkCount(bTxn));
        assertEquals(0, other.getJson("/api/v1/tags", 200).size());
        assertNotEquals(200, other.get("/api/v1/tags/" + aTag).getStatus());
        other.postJson("/api/v1/transactions", Map.of("accountId", bank(other).toString(), "date", "2026-10-05",
                "amount", -5, "description", "x", "tagIds", List.of(aTag)), 404);
    }

    @Test
    void transactionsCarryTheirTagsAndTagIdsOnUpdateReplaceThem() throws Exception {
        UUID trip = tag(api, "Trip");
        UUID gift = tag(api, "Gift");
        UUID id = api.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", "2026-10-05",
                "amount", -90, "description", "Hotel", "tagIds", List.of(trip)));

        JsonNode read = api.getJson("/api/v1/transactions/" + id, 200);
        assertEquals(1, read.get("tags").size());
        assertEquals("Trip", read.get("tags").get(0).get("name").asText());
        assertEquals(trip.toString(), read.get("tags").get(0).get("id").asText());
        assertFalse(read.has("categories"), "the old field is gone");

        api.putJson("/api/v1/transactions/" + id, Map.of("date", "2026-10-05", "amount", -90, "description", "Hotel",
                "tagIds", List.of(gift)), 200);
        JsonNode after = api.getJson("/api/v1/transactions/" + id, 200);
        assertEquals(1, after.get("tags").size());
        assertEquals("Gift", after.get("tags").get(0).get("name").asText());
    }

    @Test
    void renamingOntoAnExistingNameIsAConflict() throws Exception {
        tag(api, "Trip");
        UUID gift = tag(api, "Gift");
        JsonNode err = api.putJson("/api/v1/tags/" + gift, Map.of("name", " Trip "), 409);
        assertEquals("CONFLICT", err.get("code").asText());
        // Renaming to its own name is not a conflict.
        api.putJson("/api/v1/tags/" + gift, Map.of("name", "Gift"), 200);
    }
}
