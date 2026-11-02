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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sort them over the cached per-user group index: it follows every write that changes the uncategorised set,
 * search filters groups, and apply can be undone (rows and rule), per user.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UncategorizedIndexSearchUndoIntegrationTest {

    private static final String BASE = "/api/v1/transactions/uncategorized";

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
        api = newUser("idx-a");
        other = newUser("idx-b");
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

    private static UUID bank(ApiTestClient client, String last4) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Bank " + last4, "last4", last4,
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private UUID out(ApiTestClient c, UUID account, String description, int amount, String date) throws Exception {
        return c.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", date,
                "amount", -amount, "description", description));
    }

    private UUID out(String description, int amount, String date) throws Exception {
        return out(api, bank, description, amount, date);
    }

    private JsonNode groups(String query) throws Exception {
        return api.getJson(BASE + "/groups?size=50" + query, 200);
    }

    private static List<String> labels(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("label").asText()));
        return out;
    }

    private Map<String, Object> applyBody(List<UUID> ids, String key, String sub, Map<String, Object> rule) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("transactionIds", ids.stream().map(UUID::toString).toList());
        b.put("categoryKey", key);
        b.put("subcategoryKey", sub);
        if (rule != null) {
            b.put("rule", rule);
        }
        return b;
    }

    /** The undo body a client builds from an apply response. */
    private Map<String, Object> undoBody(JsonNode applied, String key, String sub) {
        Map<String, Object> b = new HashMap<>();
        b.put("transactionIds", mapper.convertValue(applied.get("updatedIds"), List.class));
        b.put("categoryKey", key);
        b.put("subcategoryKey", sub);
        b.put("ruleId", applied.get("ruleId").isNull() ? null : applied.get("ruleId").asText());
        b.put("ruleCreated", applied.get("ruleCreated").asBoolean());
        b.put("previousRuleCategoryKey", applied.get("previousRuleCategoryKey").isNull() ? null
                : applied.get("previousRuleCategoryKey").asText());
        b.put("previousRuleSubcategoryKey", applied.get("previousRuleSubcategoryKey").isNull() ? null
                : applied.get("previousRuleSubcategoryKey").asText());
        b.put("userClearedIds", mapper.convertValue(applied.get("userClearedIds"), List.class));
        b.put("ruleMerchantKey", applied.get("ruleMerchantKey").isNull() ? null : applied.get("ruleMerchantKey").asText());
        b.put("ruleMatchType", applied.get("ruleMatchType").isNull() ? null : applied.get("ruleMatchType").asText());
        return b;
    }

    // ------------------------------------------------------------------ index follows writes

    @Test
    void theIndexFollowsIngestCategoriseApplyAndDeleteWithoutStaleReads() throws Exception {
        UUID a1 = out("ZYXQ TRADERS", 100, "2026-10-01");
        out("ZYXQ TRADERS", 200, "2026-10-02");
        assertEquals(1, groups("").get("totalElements").asInt());
        String key = groups("").get("content").get(0).get("groupKey").asText();
        assertEquals(2, api.getJson(BASE + "/groups/" + key + "/transactions", 200).get("totalElements").asInt());

        // ingest: a new row joins the group (page, summary, group rows all see it)
        UUID a3 = out("ZYXQ TRADERS", 50, "2026-10-03");
        assertEquals(3, groups("").get("content").get(0).get("count").asInt());
        assertEquals(3, api.getJson(BASE + "/summary", 200).get("count").asInt());
        assertEquals(3, api.getJson(BASE + "/groups/" + key + "/transactions", 200).get("totalElements").asInt());

        // a hand pick leaves the set
        api.putJson("/api/v1/transactions/" + a1 + "/category", Map.of("categoryKey", "food"), 200);
        assertEquals(2, groups("").get("content").get(0).get("count").asInt());

        // apply leaves the set, undo brings it back
        JsonNode applied = api.postJson(BASE + "/apply", applyBody(List.of(a3), "shopping", null, null), 200);
        assertEquals(1, groups("").get("content").get(0).get("count").asInt());
        api.postJson(BASE + "/undo", undoBody(applied, "shopping", null), 200);
        assertEquals(2, groups("").get("content").get(0).get("count").asInt());

        // delete
        api.deleteJson("/api/v1/transactions/" + a3, 204);
        assertEquals(1, groups("").get("content").get(0).get("count").asInt());
    }

    @Test
    void aRuleCategorisesLaterIngestSoItNeverEntersTheIndex() throws Exception {
        out("RULEPAY STORE", 10, "2026-10-01");
        assertEquals(1, groups("").get("totalElements").asInt());
        api.postJson("/api/v1/rules", Map.of("merchantKey", "rulepay", "matchType", "CONTAINS", "categoryKey", "shopping"), 201);

        out("RULEPAY STORE", 20, "2026-10-02");

        JsonNode g = groups("");
        assertEquals(1, g.get("totalElements").asInt());
        assertEquals(1, g.get("content").get(0).get("count").asInt(), "only the row from before the rule");
    }

    @Test
    void aGroupKeyThatNoLongerExistsIs404AfterItsRowsAreSorted() throws Exception {
        UUID id = out("ZYXQ TRADERS", 100, "2026-10-01");
        String key = groups("").get("content").get(0).get("groupKey").asText();
        api.getJson(BASE + "/groups/" + key + "/transactions", 200);
        api.putJson("/api/v1/transactions/" + id + "/category", Map.of("categoryKey", "food"), 200);

        assertEquals(404, api.get(BASE + "/groups/" + key + "/transactions").getStatus());
    }

    @Test
    void eachUserSeesOnlyTheirOwnIndex() throws Exception {
        out("ZYXQ TRADERS", 100, "2026-10-01");
        out(other, otherBank, "THEIRS TRADERS", 10, "2026-10-01");
        String mine = groups("").get("content").get(0).get("groupKey").asText();
        other.getJson(BASE + "/groups", 200);

        assertEquals(List.of("Zyxq Traders"), labels(groups("")));
        assertEquals(List.of("Theirs Traders"), labels(other.getJson(BASE + "/groups", 200)));
        assertEquals(404, other.get(BASE + "/groups/" + mine + "/transactions").getStatus());
    }

    // ------------------------------------------------------------------ search

    @Test
    void searchFiltersGroupsByLabelCaseInsensitivelyAndPagesAfterFiltering() throws Exception {
        out("ZYXQ TRADERS", 100, "2026-10-01");
        out("ZYXQ TRADERS", 100, "2026-10-02");
        out("ABCD TRADERS", 70, "2026-10-03");
        out("EFGH STORES", 80, "2026-10-04");
        out("WVUT TRADERS", 10, "2026-10-05");

        JsonNode hit = groups("&q=tRaDeRs");
        assertEquals(List.of("Zyxq Traders", "Wvut Traders", "Abcd Traders"), labels(hit),
                "multi first, then one-offs newest first");
        assertEquals(3, hit.get("totalElements").asInt(), "totals reflect the filter");

        JsonNode page2 = api.getJson(BASE + "/groups?size=2&page=1&q=traders", 200);
        assertEquals(List.of("Abcd Traders"), labels(page2));
        assertEquals(3, page2.get("totalElements").asInt());

        assertEquals(1, groups("&q=EFGH").get("totalElements").asInt());
        assertEquals(0, groups("&q=nomatch").get("totalElements").asInt());
        assertEquals(4, groups("&q=").get("totalElements").asInt(), "blank q = no filter");
        assertEquals(4, groups("").get("totalElements").asInt());
    }

    @Test
    void searchIsScopedToTheUser() throws Exception {
        out("ZYXQ TRADERS", 100, "2026-10-01");
        out(other, otherBank, "ZYXQ OTHERS", 10, "2026-10-01");
        assertEquals(List.of("Zyxq Traders"), labels(groups("&q=zyxq")));
    }

    // ------------------------------------------------------------------ undo

    @Test
    void undoWithoutARuleSetsRowsBackAndSkipsRowsThatChangedSince() throws Exception {
        UUID a = out("ZYXQ TRADERS", 100, "2026-10-01");
        UUID b = out("ZYXQ TRADERS", 100, "2026-10-02");
        JsonNode applied = api.postJson(BASE + "/apply", applyBody(List.of(a, b), "shopping", null, null), 200);
        assertEquals(2, applied.get("updatedIds").size());
        assertTrue(applied.get("ruleId").isNull());
        assertEquals(false, applied.get("ruleCreated").asBoolean());
        // b is re-picked by hand after the apply: undo must leave it
        api.putJson("/api/v1/transactions/" + b + "/category", Map.of("categoryKey", "food"), 200);

        JsonNode r = api.postJson(BASE + "/undo", undoBody(applied, "shopping", null), 200);

        assertEquals(1, r.get("restored").asInt());
        assertEquals(1, r.get("skipped").asInt());
        assertTrue(api.getJson("/api/v1/transactions/" + a, 200).get("category").isNull());
        assertEquals("food", api.getJson("/api/v1/transactions/" + b, 200).get("category").get("key").asText());
        assertEquals(1, api.getJson(BASE + "/summary", 200).get("count").asInt());
    }

    @Test
    void undoDeletesARuleTheApplyCreated() throws Exception {
        UUID a = out("ZYXQ TRADERS", 100, "2026-10-01");
        JsonNode applied = api.postJson(BASE + "/apply",
                applyBody(List.of(a), "shopping", null, Map.of("pattern", "zyxq traders")), 200);
        assertTrue(applied.get("ruleCreated").asBoolean());
        assertEquals(1, api.getJson("/api/v1/rules", 200).get("totalElements").asInt());

        JsonNode r = api.postJson(BASE + "/undo", undoBody(applied, "shopping", null), 200);

        assertTrue(r.get("ruleDeleted").asBoolean());
        assertEquals(0, api.getJson("/api/v1/rules", 200).get("totalElements").asInt());
        assertTrue(api.getJson("/api/v1/transactions/" + a, 200).get("category").isNull());
    }

    @Test
    void undoPutsBackThePreviousTargetOfARepointedRule() throws Exception {
        Map<String, Object> rule = Map.of("pattern", "zyxq traders");
        UUID a = out("ZYXQ TRADERS", 1, "2026-10-01");
        UUID b = out("ZYXQ TRADERS", 2, "2026-10-02"); // before the rule exists, so it stays uncategorised
        api.postJson(BASE + "/apply", applyBody(List.of(a), "shopping", null, rule), 200);
        JsonNode applied = api.postJson(BASE + "/apply",
                applyBody(List.of(b), "groceries", "groceries.supermarket", rule), 200);
        assertEquals(false, applied.get("ruleCreated").asBoolean());
        assertEquals("shopping", applied.get("previousRuleCategoryKey").asText());
        assertEquals("groceries", api.getJson("/api/v1/rules", 200).get("content").get(0).get("category").get("key").asText());

        JsonNode r = api.postJson(BASE + "/undo", undoBody(applied, "groceries", "groceries.supermarket"), 200);

        assertTrue(r.get("ruleRestored").asBoolean());
        assertEquals(false, r.get("ruleDeleted").asBoolean());
        JsonNode rules = api.getJson("/api/v1/rules", 200);
        assertEquals(1, rules.get("totalElements").asInt());
        assertEquals("shopping", rules.get("content").get(0).get("category").get("key").asText());
        assertTrue(rules.get("content").get(0).get("subcategory").isNull());
    }

    @Test
    void undoLeavesARuleTheUserEditedAfterTheApply() throws Exception {
        UUID a = out("ZYXQ TRADERS", 100, "2026-10-01");
        JsonNode applied = api.postJson(BASE + "/apply",
                applyBody(List.of(a), "shopping", null, Map.of("pattern", "zyxq traders")), 200);
        String ruleId = applied.get("ruleId").asText();
        api.putJson("/api/v1/rules/" + ruleId, Map.of("merchantKey", "zyxq traders", "matchType", "CONTAINS",
                "categoryKey", "food"), 200);

        JsonNode r = api.postJson(BASE + "/undo", undoBody(applied, "shopping", null), 200);

        assertEquals(1, r.get("restored").asInt());
        assertEquals(false, r.get("ruleDeleted").asBoolean());
        assertEquals(1, api.getJson("/api/v1/rules", 200).get("totalElements").asInt());
    }

    @Test
    void undoCannotTouchAnotherUsersRowsOrRules() throws Exception {
        UUID theirs = out(other, otherBank, "THEIRS TRADERS", 10, "2026-10-01");
        JsonNode theirApply = other.postJson(BASE + "/apply",
                applyBody(List.of(theirs), "shopping", null, Map.of("pattern", "theirs traders")), 200);

        JsonNode r = api.postJson(BASE + "/undo", undoBody(theirApply, "shopping", null), 200);

        assertEquals(0, r.get("restored").asInt());
        assertEquals(1, r.get("skipped").asInt());
        assertEquals(false, r.get("ruleDeleted").asBoolean());
        assertEquals("shopping", other.getJson("/api/v1/transactions/" + theirs, 200).get("category").get("key").asText());
        assertEquals(1, other.getJson("/api/v1/rules", 200).get("totalElements").asInt());
    }

    @Test
    void undoValidatesTheBody() throws Exception {
        assertEquals(400, api.post(BASE + "/undo", Map.of("transactionIds", List.of(), "categoryKey", "food")).getStatus());
        assertEquals(400, api.post(BASE + "/undo", Map.of("transactionIds", List.of(UUID.randomUUID().toString()))).getStatus());
    }

    @Test
    void undoGivesBackTheHandClearedStateOfRowsTheUserHadCleared() throws Exception {
        UUID cleared = out("ZYXQ TRADERS", 100, "2026-10-01");
        UUID plain = out("ZYXQ TRADERS", 100, "2026-10-02");
        api.putJson("/api/v1/transactions/" + cleared + "/category", Map.of("categoryKey", "food"), 200);
        Map<String, Object> clear = new HashMap<>();
        clear.put("categoryKey", null);
        clear.put("subcategoryKey", null);
        api.putJson("/api/v1/transactions/" + cleared + "/category", clear, 200);
        assertEquals("USER", api.getJson("/api/v1/transactions/" + cleared, 200).get("categorySource").asText());

        JsonNode applied = api.postJson(BASE + "/apply", applyBody(List.of(cleared, plain), "shopping", null, null), 200);
        assertEquals(List.of(cleared.toString()), mapper.convertValue(applied.get("userClearedIds"), List.class));
        api.postJson(BASE + "/undo", undoBody(applied, "shopping", null), 200);

        JsonNode c = api.getJson("/api/v1/transactions/" + cleared, 200);
        assertTrue(c.get("category").isNull());
        assertEquals("USER", c.get("categorySource").asText(), "still a decision of the user's");
        JsonNode p = api.getJson("/api/v1/transactions/" + plain, 200);
        assertTrue(p.get("category").isNull());
        assertTrue(p.get("categorySource") == null || p.get("categorySource").isNull(), "never categorised");
    }

    @Test
    void undoWithoutUserClearedIdsClearsEverythingToNeverCategorised() throws Exception {
        UUID cleared = out("ZYXQ TRADERS", 100, "2026-10-01");
        api.putJson("/api/v1/transactions/" + cleared + "/category", Map.of("categoryKey", "food"), 200);
        Map<String, Object> clear = new HashMap<>();
        clear.put("categoryKey", null);
        clear.put("subcategoryKey", null);
        api.putJson("/api/v1/transactions/" + cleared + "/category", clear, 200);
        JsonNode applied = api.postJson(BASE + "/apply", applyBody(List.of(cleared), "shopping", null, null), 200);
        Map<String, Object> body = undoBody(applied, "shopping", null);
        body.remove("userClearedIds");

        api.postJson(BASE + "/undo", body, 200);

        JsonNode c = api.getJson("/api/v1/transactions/" + cleared, 200);
        assertTrue(c.get("categorySource") == null || c.get("categorySource").isNull());
    }

    @Test
    void undoRejectsPreviousRuleKeysThatAreNotAValidTarget() throws Exception {
        UUID a = out("ZYXQ TRADERS", 100, "2026-10-01");
        JsonNode applied = api.postJson(BASE + "/apply",
                applyBody(List.of(a), "shopping", null, Map.of("pattern", "zyxq traders")), 200);
        Map<String, Object> subWithoutCategory = undoBody(applied, "shopping", null);
        subWithoutCategory.put("ruleCreated", false);
        subWithoutCategory.put("previousRuleCategoryKey", null);
        subWithoutCategory.put("previousRuleSubcategoryKey", "groceries.supermarket");
        assertEquals(400, api.post(BASE + "/undo", subWithoutCategory).getStatus());

        Map<String, Object> unknown = undoBody(applied, "shopping", null);
        unknown.put("ruleCreated", false);
        unknown.put("previousRuleCategoryKey", "nope");
        assertEquals(400, api.post(BASE + "/undo", unknown).getStatus());

        Map<String, Object> mismatched = undoBody(applied, "shopping", null);
        mismatched.put("ruleCreated", false);
        mismatched.put("previousRuleCategoryKey", "food");
        mismatched.put("previousRuleSubcategoryKey", "groceries.supermarket");
        assertEquals(400, api.post(BASE + "/undo", mismatched).getStatus());
        assertEquals("shopping", api.getJson("/api/v1/transactions/" + a, 200).get("category").get("key").asText(),
                "a rejected undo changes nothing");
    }

    @Test
    void undoLeavesARuleWhoseMatchTypeChangedAfterTheApply() throws Exception {
        UUID a = out("ZYXQ TRADERS", 100, "2026-10-01");
        JsonNode applied = api.postJson(BASE + "/apply",
                applyBody(List.of(a), "shopping", null, Map.of("pattern", "zyxq traders")), 200);
        assertEquals("CONTAINS", applied.get("ruleMatchType").asText());
        api.putJson("/api/v1/rules/" + applied.get("ruleId").asText(), Map.of("merchantKey", "zyxq traders",
                "matchType", "EXACT", "categoryKey", "shopping"), 200);

        JsonNode r = api.postJson(BASE + "/undo", undoBody(applied, "shopping", null), 200);

        assertEquals(false, r.get("ruleDeleted").asBoolean());
        assertEquals(1, api.getJson("/api/v1/rules", 200).get("totalElements").asInt());
    }

    @Test
    void undoLeavesARuleWhosePatternChangedAfterTheApply() throws Exception {
        UUID a = out("ZYXQ TRADERS", 100, "2026-10-01");
        JsonNode applied = api.postJson(BASE + "/apply",
                applyBody(List.of(a), "shopping", null, Map.of("pattern", "zyxq traders")), 200);
        api.putJson("/api/v1/rules/" + applied.get("ruleId").asText(), Map.of("merchantKey", "zyxq",
                "matchType", "CONTAINS", "categoryKey", "shopping"), 200);

        JsonNode r = api.postJson(BASE + "/undo", undoBody(applied, "shopping", null), 200);

        assertEquals(false, r.get("ruleDeleted").asBoolean());
        assertEquals(1, api.getJson("/api/v1/rules", 200).get("totalElements").asInt());
    }
}
