package com.financeos.api.transaction;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** GET /transactions/uncategorized/exists: true/false, tenancy, and agreement with the summary. */
@SpringBootTest
@AutoConfigureMockMvc
class UncategorizedExistsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private final List<UUID> userIds = new ArrayList<>();
    private ApiTestClient api;
    private ApiTestClient other;
    private UUID bank;
    private UUID otherBank;

    @BeforeEach
    void setUp() throws Exception {
        api = newUser("exists-a");
        other = newUser("exists-b");
        bank = bank(api, "1234");
        otherBank = bank(other, "9999");
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

    private UUID bank(ApiTestClient client, String last4) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank " + last4, "last4", last4,
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID txn(ApiTestClient client, UUID account, int amount, String category, boolean excluded) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", AppTime.today().minusDays(1).toString());
        body.put("amount", amount);
        body.put("description", "row " + UUID.randomUUID());
        if (category != null) {
            body.put("categoryKey", category);
        }
        if (excluded) {
            body.put("isTransactionExcluded", true);
        }
        return client.create("/api/v1/transactions", body);
    }

    private UUID uncategorised(ApiTestClient client, UUID account, int amount, boolean excluded) throws Exception {
        UUID id = txn(client, account, amount, null, excluded);
        jdbc.update("UPDATE transactions SET category_key = NULL, subcategory_key = NULL, category_source = NULL, "
                + "category_set_at = NULL WHERE id = ?", id.toString());
        return id;
    }

    private void assertAgrees(boolean expected) throws Exception {
        boolean exists = api.getJson("/api/v1/transactions/uncategorized/exists", 200).get("exists").asBoolean();
        long count = api.getJson("/api/v1/transactions/uncategorized/summary", 200).get("count").asLong();
        assertEquals(expected, exists);
        assertEquals(count > 0, exists, "exists must equal summary.count > 0");
    }

    @Test
    void noTransactionsAtAllIsFalse() throws Exception {
        assertAgrees(false);
    }

    @Test
    void onlyCategorisedRowsIsFalse() throws Exception {
        txn(api, bank, -100, "food", false);
        txn(api, bank, 500, "salary", false);
        assertAgrees(false);
    }

    @Test
    void anUncategorisedDebitIsTrue() throws Exception {
        uncategorised(api, bank, -100, false);
        assertAgrees(true);
    }

    @Test
    void anUncategorisedCreditIsTrue() throws Exception {
        uncategorised(api, bank, 100, false);
        assertAgrees(true);
    }

    @Test
    void anExcludedUncategorisedRowStillCounts() throws Exception {
        uncategorised(api, bank, -100, true);
        assertAgrees(true);
    }

    @Test
    void aRowTheUserClearedStillCounts() throws Exception {
        UUID id = txn(api, bank, -100, "food", false);
        jdbc.update("UPDATE transactions SET category_key = NULL, subcategory_key = NULL, category_source = 'USER' WHERE id = ?",
                id.toString());
        assertAgrees(true);
    }

    @Test
    void anotherUsersUncategorisedRowsDoNotCount() throws Exception {
        uncategorised(other, otherBank, -100, false);
        txn(api, bank, -50, "food", false);
        assertAgrees(false);
        assertEquals(true, other.getJson("/api/v1/transactions/uncategorized/exists", 200).get("exists").asBoolean());
    }

    @Test
    void categorisingTheLastRowFlipsItToFalse() throws Exception {
        UUID id = uncategorised(api, bank, -100, false);
        assertAgrees(true);
        jdbc.update("UPDATE transactions SET category_key = 'food', category_source = 'USER' WHERE id = ?", id.toString());
        assertAgrees(false);
    }
}
