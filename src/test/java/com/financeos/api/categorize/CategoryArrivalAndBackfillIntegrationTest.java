package com.financeos.api.categorize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.categorization.CategoryBackfillProgress;
import com.financeos.domain.categorization.CategoryBackfillProgressRepository;
import com.financeos.domain.categorization.CategoryBackfillService;
import com.financeos.domain.job.JobRepository;
import com.financeos.domain.job.JobType;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Categorise-on-arrival (manual add), link hooks over HTTP, and the one-off backfill, end to end on H2.
 * The job worker is off so the enqueued backfill job just sits PENDING and can be counted.
 */
@SpringBootTest(properties = {"categorize.backfill.enabled=true", "jobs.worker.enabled=false"})
@AutoConfigureMockMvc
class CategoryArrivalAndBackfillIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryBackfillService backfill;
    @Autowired private CategoryBackfillProgressRepository progressRepository;
    @Autowired private JobRepository jobRepository;

    private final LocalDate today = AppTime.today();
    private ApiTestClient api;
    private UUID userId;
    private UUID bank;
    private UUID bank2;

    @BeforeEach
    void setUp() throws Exception {
        String email = "arrival-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Main", "last4", "1111",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        bank2 = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Side", "last4", "2222",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
        jdbc.update("DELETE FROM category_backfill_progress WHERE user_id = ?", CategoryBackfillProgress.RUN);
    }

    private UUID txn(UUID account, int signedAmount, String description, String category) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", today.minusDays(1).toString());
        body.put("amount", signedAmount);
        body.put("description", description);
        if (category != null) {
            body.put("categoryKey", category);
        }
        return api.create("/api/v1/transactions", body);
    }

    private JsonNode read(UUID id) throws Exception {
        return api.getJson("/api/v1/transactions/" + id, 200);
    }

    private String key(JsonNode t) {
        return t.get("category").isNull() ? null : t.get("category").get("key").asText();
    }

    private String sub(JsonNode t) {
        return t.get("subcategory") == null || t.get("subcategory").isNull() ? null : t.get("subcategory").get("key").asText();
    }

    private String source(JsonNode t) {
        return t.get("categorySource").isNull() ? null : t.get("categorySource").asText();
    }

    private UUID link(String type, UUID anchor, UUID counterpart, boolean alignRefund) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("alignRefundCategories", alignRefund);
        body.put("members", List.of(Map.of("transactionId", anchor.toString(), "isAnchor", true),
                Map.of("transactionId", counterpart.toString(), "isAnchor", false)));
        return api.create("/api/v1/transaction-links", body);
    }

    // ---- arrival

    @Test
    void aManualAddWithoutACategoryGetsTheEngineAnswerOnce() throws Exception {
        UUID food = txn(bank, -250, "SWIGGY", null);
        JsonNode t = read(food);
        assertEquals("food", key(t));
        assertEquals("food.delivery", sub(t));
        assertEquals("AUTO", source(t));

        // editing never re-runs it: a row nothing knew stays uncategorised when its description changes
        UUID unknown = txn(bank, -100, "ZYXQ WVUT TRADERS", null);
        assertEquals(null, key(read(unknown)));
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("description", "SWIGGY");
        edit.put("date", today.minusDays(1).toString());
        edit.put("amount", -100);
        api.putJson("/api/v1/transactions/" + unknown, edit, 200);
        assertEquals(null, key(read(unknown)));
    }

    @Test
    void aPickedCategoryAndAPaymentToAPersonAreLeftAlone() throws Exception {
        JsonNode picked = read(txn(bank, -250, "SWIGGY", "travel"));
        assertEquals("travel", key(picked));
        assertEquals("USER", source(picked));
        assertEquals(null, key(read(txn(bank, -300, "UPI_MR RAVI KUMAR SHARMA", null))));
    }

    @Test
    void aRuleBeatsTheEngineOnArrival() throws Exception {
        api.postJson("/api/v1/rules", Map.of("merchantKey", "swiggy", "matchType", "CONTAINS", "categoryKey", "groceries"), 201);
        JsonNode t = read(txn(bank, -250, "SWIGGY ORDER", null));
        assertEquals("groceries", key(t));
        assertEquals("RULE", source(t));
    }

    // ---- links

    @Test
    void transferLinkSetsSelfTransferAndUnlinkingRecategorises() throws Exception {
        UUID out = txn(bank, -500, "ZYXQ MOVE", null);
        UUID in = txn(bank2, 500, "ZYXQ MOVE", null);
        UUID link = link("TRANSFER", out, in, false);
        for (UUID id : List.of(out, in)) {
            JsonNode t = read(id);
            assertEquals("self_transfer", key(t));
            assertEquals("LINK", source(t));
        }
        api.deleteJson("/api/v1/transaction-links/" + link, 204);
        for (UUID id : List.of(out, in)) {
            JsonNode t = read(id);
            assertEquals(null, key(t));
            assertEquals(null, source(t));
        }
    }

    @Test
    void aLinkNeverOverridesARuleOrTheUsersPick() throws Exception {
        api.postJson("/api/v1/rules", Map.of("merchantKey", "zyxq rule", "matchType", "CONTAINS", "categoryKey", "salary"), 201);
        UUID out = txn(bank, -500, "ZYXQ MOVE", "food");
        UUID in = txn(bank2, 500, "ZYXQ RULE MOVE", null);
        assertEquals("RULE", source(read(in)));
        link("TRANSFER", out, in, false);
        assertEquals("food", key(read(out)));
        assertEquals("USER", source(read(out)));
        assertEquals("salary", key(read(in)));
        assertEquals("RULE", source(read(in)));
    }

    @Test
    void feeAndRefundLinksSetTheirCategories() throws Exception {
        UUID parent = txn(bank, -1000, "ZYXQ PURCHASE", null);
        UUID fee = txn(bank, -20, "ZYXQ FEE", null);
        link("FEE", parent, fee, false);
        assertEquals("fees", key(read(fee)));
        assertEquals(null, key(read(parent)));

        UUID bought = txn(bank, -300, "SWIGGY", null);
        UUID refund = txn(bank, 300, "ZYXQ BACK", null);
        link("REFUND", bought, refund, true);
        assertEquals("food", key(read(refund)));
        assertEquals("LINK", source(read(refund)));
    }

    // ---- lending

    @Test
    void lendingEntriesSetLentBorrowedRepaidAndUnlinkingRecategorises() throws Exception {
        UUID out = txn(bank, -500, "ZYXQ LENT", null);
        UUID in = txn(bank, 500, "ZYXQ BORROWED", null);
        UUID back = txn(bank, 200, "ZYXQ BACK", null);

        Map<String, Object> lent = new LinkedHashMap<>();
        lent.put("newCounterpartyName", "Asha");
        lent.put("direction", "lent");
        lent.put("amount", 500);
        lent.put("entryDate", today.minusDays(1).toString());
        lent.put("transactionId", out.toString());
        UUID lentId = api.create("/api/v1/lendings", lent);
        assertEquals("lent", key(read(out)));
        assertEquals("LINK", source(read(out)));

        Map<String, Object> borrowed = new LinkedHashMap<>(lent);
        borrowed.put("newCounterpartyName", "Ravi");
        borrowed.put("direction", "borrowed");
        borrowed.put("transactionId", in.toString());
        api.create("/api/v1/lendings", borrowed);
        assertEquals("borrowed", key(read(in)));

        Map<String, Object> settle = new LinkedHashMap<>(borrowed);
        settle.put("newCounterpartyName", "Ravi");
        settle.put("kind", "settlement");
        settle.put("amount", 200);
        settle.put("transactionId", null);
        UUID settleId = api.create("/api/v1/lendings", settle);
        api.putJson("/api/v1/lendings/" + settleId + "/transaction", Map.of("transactionId", back.toString()), 200);
        assertEquals("repaid", key(read(back)));

        api.deleteJson("/api/v1/lendings/" + lentId + "/transaction", 204);
        assertEquals(null, key(read(out)));
        api.deleteJson("/api/v1/lendings/" + settleId, 204);
        assertEquals(null, key(read(back)));
    }

    // ---- backfill

    private UUID uncategorised(UUID account, int amount, String description) throws Exception {
        UUID id = txn(account, amount, description, null);
        jdbc.update("UPDATE transactions SET category_key = NULL, subcategory_key = NULL, category_source = NULL, "
                + "category_set_at = NULL WHERE id = ?", id.toString());
        return id;
    }

    @Test
    void backfillFillsOldRowsKeepsTotalsAndSkipsWhatTheUserCleared() throws Exception {
        UUID old = uncategorised(bank, -250, "SWIGGY");
        jdbc.update("UPDATE transactions SET is_excluded = 1 WHERE id = ?", old.toString());
        UUID unknown = uncategorised(bank, -100, "ZYXQ WVUT TRADERS");
        UUID cleared = uncategorised(bank, -100, "UBER");
        jdbc.update("UPDATE transactions SET category_source = 'USER' WHERE id = ?", cleared.toString());

        // The startup backfill job may walk this user first, so the counts are read from the
        // user's progress row (all runs), not from this run's result.
        backfill.run(null);

        JsonNode filled = read(old);
        assertEquals("food", key(filled));
        assertEquals("AUTO", source(filled));
        Number excluded = jdbc.queryForObject("SELECT is_excluded FROM transactions WHERE id = ?", Number.class, old.toString());
        assertEquals(1, excluded.intValue(), "backfill must not change is_excluded");
        assertEquals(null, key(read(unknown)));
        assertEquals(null, key(read(cleared)), "a row the user cleared stays cleared");
        assertEquals("USER", source(read(cleared)));
        assertTrue(backfill.isCompleted());
        CategoryBackfillProgress mine = progressRepository
                .findById(new CategoryBackfillProgress.Key(CategoryBackfillService.KEY, userId.toString())).orElseThrow();
        assertTrue(mine.isDone());
        assertTrue(mine.getScanned() >= 2);
        assertTrue(mine.getCategorised() >= 1);
    }

    @Test
    void backfillResumesAfterTheLastWalkedRowAndRunsOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(uncategorised(bank, -100 - i, "SWIGGY"));
        }
        List<String> sorted = jdbc.queryForList("SELECT id FROM transactions WHERE user_id = ? ORDER BY id", String.class, userId.toString());
        assertEquals(3, sorted.size());
        // a restart left progress after the second row
        CategoryBackfillProgress progress = new CategoryBackfillProgress(CategoryBackfillService.KEY, userId.toString());
        progress.setLastTxnId(UUID.fromString(sorted.get(1)));
        progressRepository.save(progress);

        backfill.runUser(userId, null);

        assertEquals(null, key(read(UUID.fromString(sorted.get(0)))), "walked before the restart: not looked at again");
        assertEquals(null, key(read(UUID.fromString(sorted.get(1)))));
        assertEquals("food", key(read(UUID.fromString(sorted.get(2)))));
        // finished users are not walked again
        jdbc.update("UPDATE transactions SET category_key = NULL, category_source = NULL WHERE id = ?", sorted.get(2));
        int[] again = backfill.runUser(userId, null);
        assertEquals(0, again[0]);
        assertEquals(null, key(read(UUID.fromString(sorted.get(2)))));
    }

    @Test
    void theBackfillIsEnqueuedOnceAndNeverAgainOnceCompleted() {
        jdbc.update("DELETE FROM jobs WHERE type = 'CATEGORY_BACKFILL'");
        jdbc.update("DELETE FROM category_backfill_progress WHERE user_id = ?", CategoryBackfillProgress.RUN);
        backfill.enqueueIfPending();
        backfill.enqueueIfPending();
        assertEquals(1, jobRepository.findAll().stream().filter(j -> j.getType() == JobType.CATEGORY_BACKFILL).count(),
                "the dedup key keeps one live job");

        backfill.run(null);
        assertTrue(backfill.isCompleted());
        jdbc.update("DELETE FROM jobs WHERE type = 'CATEGORY_BACKFILL'");
        backfill.enqueueIfPending();
        assertFalse(jobRepository.findAll().stream().anyMatch(j -> j.getType() == JobType.CATEGORY_BACKFILL),
                "a completed backfill is never enqueued again");
    }
}
