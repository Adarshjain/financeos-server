package com.financeos.api.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /api/v1/transactions/{id} over real persistence: the caller's transaction comes back with the
 * same mapping as its row in the transactions list (categories, links, obligation refs), without the
 * list's running balance; another user's or an unknown id is 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionGetByIdIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private UUID transactionId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "txn-by-id-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "txn-by-id-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();

        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Lookup Bank", "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID category = api.create("/api/v1/categories", Map.of("name", "Lent out"));
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("accountId", bank.toString());
        txn.put("date", AppTime.today().minusDays(2).toString());
        txn.put("amount", -5000);
        txn.put("description", "Transfer to Ravi");
        txn.put("categoryIds", List.of(category.toString()));
        transactionId = api.create("/api/v1/transactions", txn);
        // A lending entry linked to the transaction gives it an obligation ref.
        Map<String, Object> lending = new LinkedHashMap<>();
        lending.put("newCounterpartyName", "Ravi");
        lending.put("direction", "lent");
        lending.put("amount", 5000);
        lending.put("entryDate", AppTime.today().minusDays(2).toString());
        lending.put("transactionId", transactionId.toString());
        api.postJson("/api/v1/lendings", lending, 200);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    @Test
    void ownTransactionHasTheListRowsMappingWithoutTheRunningBalance() throws Exception {
        JsonNode single = api.getJson("/api/v1/transactions/" + transactionId, 200);
        JsonNode listRow = null;
        for (JsonNode row : api.getJson("/api/v1/transactions?size=50", 200).get("content")) {
            if (row.get("id").asText().equals(transactionId.toString())) {
                listRow = row;
            }
        }

        assertTrue(listRow != null, "the transaction is listed");
        assertTrue(single.get("balance").isNull(), "no running balance on a single transaction");
        assertTrue(single.get("obligationRefs").size() > 0, "the lending ref is mapped: " + single);
        assertEquals("Lent out", single.get("categories").get(0).get("name").asText());
        ObjectNode expected = ((ObjectNode) listRow.deepCopy());
        ObjectNode actual = ((ObjectNode) single.deepCopy());
        expected.remove("balance");
        actual.remove("balance");
        assertEquals(expected, actual);
    }

    @Test
    void anotherUsersTransactionIsNotFound() throws Exception {
        assertEquals(404, otherApi.get("/api/v1/transactions/" + transactionId).getStatus());
    }

    @Test
    void unknownTransactionIsNotFound() throws Exception {
        assertEquals(404, api.get("/api/v1/transactions/" + UUID.randomUUID()).getStatus());
    }
}
