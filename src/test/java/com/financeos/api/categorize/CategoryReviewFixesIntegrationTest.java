package com.financeos.api.categorize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.categorization.CategoryAssigner;
import com.financeos.domain.categorization.CategoryBackfillService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review fixes before prod: explicit exclusion on create, backfill fault isolation, apply pattern length. */
@SpringBootTest(properties = {"categorize.backfill.enabled=false", "jobs.worker.enabled=false"})
@AutoConfigureMockMvc
class CategoryReviewFixesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryBackfillService backfill;
    @SpyBean private CategoryAssigner assigner;

    private ApiTestClient api;
    private UUID userId;
    private UUID bank;

    @BeforeEach
    void setUp() throws Exception {
        String email = "review-fixes-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main", "last4", "1111",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(assigner);
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
        jdbc.update("DELETE FROM category_backfill_progress WHERE user_id = ?", userId.toString());
    }

    private UUID txn(String description, String category, Boolean excluded) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", bank.toString());
        body.put("date", AppTime.today().minusDays(1).toString());
        body.put("amount", -100);
        body.put("description", description);
        if (category != null) {
            body.put("categoryKey", category);
        }
        if (excluded != null) {
            body.put("isTransactionExcluded", excluded);
        }
        return api.create("/api/v1/transactions", body);
    }

    private UUID uncategorised(String description) throws Exception {
        UUID id = txn(description, null, null);
        jdbc.update("UPDATE transactions SET category_key = NULL, subcategory_key = NULL, category_source = NULL, "
                + "category_set_at = NULL WHERE id = ?", id.toString());
        return id;
    }

    private boolean excluded(UUID id) throws Exception {
        return api.getJson("/api/v1/transactions/" + id, 200).get("isTransactionExcluded").asBoolean();
    }

    @Test
    void anExplicitNotExcludedOnCreateSurvivesAnExcludedByDefaultCategory() throws Exception {
        assertFalse(excluded(txn("to my other account", "self_transfer", false)));
    }

    @Test
    void withoutAnExplicitChoiceTheCategoryDefaultStillDrivesExclusion() throws Exception {
        assertTrue(excluded(txn("to my other account", "self_transfer", null)));
    }

    @Test
    void anExplicitExcludedOnCreateSurvivesANormalCategory() throws Exception {
        assertTrue(excluded(txn("lunch", "food", true)));
    }

    private static void assertFalse(boolean b) {
        org.junit.jupiter.api.Assertions.assertFalse(b);
    }

    @Test
    void aFailingRowIsSkippedTheRestAreFilledAndTheUserFinishes() throws Exception {
        UUID ok1 = uncategorised("SWIGGY ORDER 1");
        UUID bad = uncategorised("BOOM");
        UUID ok2 = uncategorised("SWIGGY ORDER 2");
        Mockito.doAnswer(inv -> {
            List<Transaction> rows = inv.getArgument(0);
            if (rows.stream().anyMatch(t -> "BOOM".equals(t.getDescription()))) {
                throw new IllegalStateException("boom");
            }
            return inv.callRealMethod();
        }).when(assigner).backfill(Mockito.anyList());

        int[] counts = backfill.runUser(userId, null);

        assertEquals(3, counts[0], "all three rows were walked past");
        assertEquals(1, counts[2], "one failure recorded");
        assertEquals("food", api.getJson("/api/v1/transactions/" + ok1, 200).get("category").get("key").asText());
        assertEquals("food", api.getJson("/api/v1/transactions/" + ok2, 200).get("category").get("key").asText());
        assertTrue(api.getJson("/api/v1/transactions/" + bad, 200).get("category").isNull());
        // the user is marked done: a second run walks nothing
        assertEquals(0, backfill.runUser(userId, null)[0]);
    }

    @Test
    void anApplyRulePatternLongerThan255IsRejected() throws Exception {
        Map<String, Object> body = Map.of("transactionIds", List.of(UUID.randomUUID().toString()),
                "categoryKey", "food", "rule", Map.of("pattern", "x".repeat(256)));
        assertEquals(400, api.post("/api/v1/transactions/uncategorized/apply", body).getStatus());
    }
}
