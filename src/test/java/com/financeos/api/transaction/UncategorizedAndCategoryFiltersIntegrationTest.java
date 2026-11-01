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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Recent picks, category counts, Sort-them groups and apply, and the category/tag list filters. */
@SpringBootTest
@AutoConfigureMockMvc
class UncategorizedAndCategoryFiltersIntegrationTest {

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
        api = newUser("unc-a");
        other = newUser("unc-b");
        bank = bank(api, "Main Bank", "1234");
        otherBank = bank(other, "Their Bank", "9999");
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

    private static UUID bank(ApiTestClient client, String name, String last4) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", last4,
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    /** Negative amount = money out. Nonsense descriptions stay uncategorised (the engine knows nobody by them). */
    private UUID txn(ApiTestClient client, UUID account, int amount, String description, String date) throws Exception {
        return client.create("/api/v1/transactions", Map.of("accountId", account.toString(), "date", date,
                "amount", amount, "description", description));
    }

    private UUID out(String description, int amount, String date) throws Exception {
        return txn(api, bank, -amount, description, date);
    }

    private UUID in(String description, int amount, String date) throws Exception {
        return txn(api, bank, amount, description, date);
    }

    private void pick(ApiTestClient client, UUID id, String key, String sub) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("categoryKey", key);
        body.put("subcategoryKey", sub);
        client.putJson("/api/v1/transactions/" + id + "/category", body, 200);
    }

    private JsonNode search(List<Map<String, Object>> filters) throws Exception {
        return api.postJson("/api/v1/transactions/search", Map.of("filters", filters), 200);
    }

    private static Map<String, Object> filter(String field, String op, Object value) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", field);
        f.put("operator", op);
        f.put("value", value);
        return f;
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    private JsonNode groupOf(JsonNode groups, String label, String direction) {
        for (JsonNode g : groups.get("content")) {
            if (g.get("label").asText().equals(label) && g.get("direction").asText().equals(direction)) {
                return g;
            }
        }
        throw new AssertionError("no group " + label + "/" + direction + " in " + groups);
    }

    /** 3 + 2 payees with several rows, a person with 2 rows, and a few one-offs (one of them money in). */
    private Map<String, List<UUID>> seed() throws Exception {
        Map<String, List<UUID>> m = new LinkedHashMap<>();
        m.put("zyxq", List.of(out("ZYXQ TRADERS", 100, "2026-10-01"), out("ZYXQ TRADERS", 200, "2026-10-03"),
                out("ZYXQ TRADERS", 50, "2026-10-02")));
        m.put("wvut", List.of(out("WVUT TRADERS", 500, "2026-10-05"), out("WVUT TRADERS", 10, "2026-10-04")));
        m.put("ravi", List.of(out("UPI/123456789012/RAVI KUMAR/ravi.kumar@ybl/Payment from Phone", 300, "2026-10-06"),
                out("UPI/223456789012/RAVI KUMAR/ravi.kumar@ybl/Payment from Phone", 20, "2026-10-07")));
        m.put("oneoffs", List.of(out("ABCD TRADERS", 70, "2026-10-08"), out("EFGH TRADERS", 80, "2026-10-09"),
                in("ZYXQ TRADERS", 90, "2026-10-10")));
        return m;
    }

    // ------------------------------------------------------------------ summary and groups

    @Test
    void summaryCountsEveryUncategorisedRowAndNamesTheThreeBiggestGroups() throws Exception {
        seed();
        pick(api, out("TAKEN", 5, "2026-10-11"), "food", null); // categorised: not counted

        JsonNode s = api.getJson("/api/v1/transactions/uncategorized/summary", 200);

        assertEquals(10, s.get("count").asInt());
        assertEquals(3, s.get("groupCount").asInt(), "groups of two or more payments");
        JsonNode top = s.get("topGroups");
        assertEquals(3, top.size());
        assertEquals("Zyxq Traders", top.get(0).get("label").asText());
        assertEquals(3, top.get(0).get("count").asInt());
        assertEquals(2, top.get(1).get("count").asInt());
    }

    @Test
    void aRowTheUserClearedStillNeedsACategory() throws Exception {
        UUID id = out("ZYXQ TRADERS", 100, "2026-10-01");
        pick(api, id, "food", null);
        Map<String, Object> clear = new HashMap<>();
        clear.put("categoryKey", null);
        clear.put("subcategoryKey", null);
        api.putJson("/api/v1/transactions/" + id + "/category", clear, 200);

        assertEquals(1, api.getJson("/api/v1/transactions/uncategorized/summary", 200).get("count").asInt());
    }

    @Test
    void groupsAreByCountThenTotalAndOneOffsComeLastAsGroupsOfOne() throws Exception {
        seed();

        JsonNode page = api.getJson("/api/v1/transactions/uncategorized/groups?size=50", 200);
        JsonNode content = page.get("content");

        assertEquals(6, page.get("totalElements").asInt());
        assertEquals(List.of("Zyxq Traders", "Wvut Traders", "Ravi Kumar"),
                List.of(content.get(0).get("label").asText(), content.get(1).get("label").asText(),
                        content.get(2).get("label").asText()), "count desc, then total desc (510 before 320)");
        JsonNode zyxq = content.get(0);
        assertEquals("OUT", zyxq.get("direction").asText());
        assertEquals(3, zyxq.get("count").asInt());
        assertEquals(0, zyxq.get("total").decimalValue().compareTo(new java.math.BigDecimal("350")));
        assertEquals("2026-10-01", zyxq.get("firstDate").asText());
        assertEquals("2026-10-03", zyxq.get("lastDate").asText());
        assertFalse(zyxq.get("oneOff").asBoolean());
        assertFalse(zyxq.get("person").asBoolean());
        for (int i = 0; i < 3; i++) {
            assertFalse(content.get(i).get("oneOff").asBoolean());
        }
        for (int i = 3; i < 6; i++) {
            assertTrue(content.get(i).get("oneOff").asBoolean());
            assertEquals(1, content.get(i).get("count").asInt());
        }
        // one-offs are newest first; the money-in "Zyxq Traders" is a group of its own, apart from the money-out one
        assertEquals("IN", content.get(3).get("direction").asText());
        assertEquals("Zyxq Traders", content.get(3).get("label").asText());
        assertNotEquals(zyxq.get("groupKey").asText(), content.get(3).get("groupKey").asText());
    }

    @Test
    void aPersonGroupIsFlaggedAndUsesThePayeeName() throws Exception {
        seed();
        JsonNode groups = api.getJson("/api/v1/transactions/uncategorized/groups?size=50", 200);
        JsonNode ravi = groupOf(groups, "Ravi Kumar", "OUT");
        assertTrue(ravi.get("person").asBoolean());
        assertEquals(2, ravi.get("count").asInt());
    }

    @Test
    void groupsPageThroughMultiGroupsAndOneOffsWithOneSequence() throws Exception {
        seed();
        List<String> keys = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode p = api.getJson("/api/v1/transactions/uncategorized/groups?size=2&page=" + page, 200);
            assertEquals(6, p.get("totalElements").asInt());
            assertEquals(3, p.get("totalPages").asInt());
            p.get("content").forEach(g -> keys.add(g.get("groupKey").asText()));
        }
        assertEquals(6, keys.size());
        assertEquals(6, keys.stream().distinct().count());
        assertEquals(0, api.getJson("/api/v1/transactions/uncategorized/groups?size=2&page=3", 200).get("content").size());
    }

    @Test
    void groupKeysAreStableUrlSafeAndPerLabelAndDirection() throws Exception {
        seed();
        String first = groupOf(api.getJson("/api/v1/transactions/uncategorized/groups?size=50", 200),
                "Zyxq Traders", "OUT").get("groupKey").asText();
        out("ZYXQ TRADERS", 1, "2026-10-12"); // more rows, same key
        String second = groupOf(api.getJson("/api/v1/transactions/uncategorized/groups?size=50", 200),
                "Zyxq Traders", "OUT").get("groupKey").asText();
        assertEquals(first, second);
        assertTrue(first.matches("[0-9a-f]{20}"), first);
    }

    @Test
    void sameMerchantOnDifferentStatementLinesIsOneGroup() throws Exception {
        out("POS 4111XXXXXXXX1234 ZYXQ TRADERS BLR", 100, "2026-10-01");
        out("UPI/412345678901/ZYXQ TRADERS/zyxqtraders@icici/Payment from Phone", 100, "2026-10-02");
        JsonNode groups = api.getJson("/api/v1/transactions/uncategorized/groups?size=50", 200);
        assertEquals(1, groups.get("totalElements").asInt());
        assertEquals(2, groups.get("content").get(0).get("count").asInt());
    }

    // ------------------------------------------------------------------ group rows

    @Test
    void groupTransactionsAreNewestFirstWithAccountAndSignedAmount() throws Exception {
        Map<String, List<UUID>> seeded = seed();
        String key = groupOf(api.getJson("/api/v1/transactions/uncategorized/groups?size=50", 200),
                "Zyxq Traders", "OUT").get("groupKey").asText();

        JsonNode page = api.getJson("/api/v1/transactions/uncategorized/groups/" + key + "/transactions", 200);

        assertEquals(3, page.get("totalElements").asInt());
        JsonNode rows = page.get("content");
        assertEquals(List.of("2026-10-03", "2026-10-02", "2026-10-01"),
                List.of(rows.get(0).get("date").asText(), rows.get(1).get("date").asText(), rows.get(2).get("date").asText()));
        JsonNode r = rows.get(0);
        assertEquals(seeded.get("zyxq").get(1).toString(), r.get("id").asText());
        assertEquals("Main Bank", r.get("accountName").asText());
        assertEquals("1234", r.get("accountLast4").asText());
        assertEquals(bank.toString(), r.get("accountId").asText());
        assertEquals("manual", r.get("source").asText());
        assertEquals("DEBIT", r.get("type").asText());
        assertEquals(0, r.get("amount").decimalValue().compareTo(new java.math.BigDecimal("-200")));
        assertEquals("ZYXQ TRADERS", r.get("description").asText());

        JsonNode second = api.getJson("/api/v1/transactions/uncategorized/groups/" + key + "/transactions?size=2&page=1", 200);
        assertEquals(1, second.get("content").size());
        assertEquals("2026-10-01", second.get("content").get(0).get("date").asText());
    }

    @Test
    void aGroupKeyOfAnotherUserOrAnUnknownOneIs404() throws Exception {
        seed();
        String key = groupOf(api.getJson("/api/v1/transactions/uncategorized/groups?size=50", 200),
                "Zyxq Traders", "OUT").get("groupKey").asText();
        assertEquals(404, other.get("/api/v1/transactions/uncategorized/groups/" + key + "/transactions").getStatus());
        assertEquals(404, api.get("/api/v1/transactions/uncategorized/groups/nonsense/transactions").getStatus());
    }

    @Test
    void anotherUsersRowsNeverShowInMySummaryOrGroups() throws Exception {
        seed();
        txn(other, otherBank, -100, "ZYXQ TRADERS", "2026-10-01");
        txn(other, otherBank, -100, "ZYXQ TRADERS", "2026-10-02");

        assertEquals(10, api.getJson("/api/v1/transactions/uncategorized/summary", 200).get("count").asInt());
        assertEquals(3, groupOf(api.getJson("/api/v1/transactions/uncategorized/groups?size=50", 200),
                "Zyxq Traders", "OUT").get("count").asInt());
        JsonNode theirs = other.getJson("/api/v1/transactions/uncategorized/summary", 200);
        assertEquals(2, theirs.get("count").asInt());
        assertEquals(1, theirs.get("groupCount").asInt());
    }

    @Test
    void nothingToSortGivesZeroes() throws Exception {
        JsonNode s = api.getJson("/api/v1/transactions/uncategorized/summary", 200);
        assertEquals(0, s.get("count").asInt());
        assertEquals(0, s.get("groupCount").asInt());
        assertEquals(0, s.get("topGroups").size());
        assertEquals(0, api.getJson("/api/v1/transactions/uncategorized/groups", 200).get("totalElements").asInt());
    }

    // ------------------------------------------------------------------ apply

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

    @Test
    void applySetsTheUsersPickOnEveryUncategorisedOwnedRow() throws Exception {
        Map<String, List<UUID>> seeded = seed();

        JsonNode r = api.postJson("/api/v1/transactions/uncategorized/apply",
                applyBody(seeded.get("zyxq"), "shopping", "shopping.general", null), 200);

        assertEquals(3, r.get("updated").asInt());
        assertEquals(0, r.get("skipped").asInt());
        assertTrue(r.get("ruleId").isNull());
        JsonNode t = api.getJson("/api/v1/transactions/" + seeded.get("zyxq").get(0), 200);
        assertEquals("shopping", t.get("category").get("key").asText());
        assertEquals("shopping.general", t.get("subcategory").get("key").asText());
        assertEquals("USER", t.get("categorySource").asText());
        assertEquals(7, api.getJson("/api/v1/transactions/uncategorized/summary", 200).get("count").asInt());
    }

    @Test
    void applySkipsForeignUnknownCategorisedAndWrongDirectionRows() throws Exception {
        Map<String, List<UUID>> seeded = seed();
        UUID foreign = txn(other, otherBank, -10, "THEIRS TRADERS", "2026-10-01");
        UUID already = out("ALREADY TRADERS", 10, "2026-10-01");
        pick(api, already, "food", null);
        UUID moneyIn = seeded.get("oneoffs").get(2);
        List<UUID> ids = List.of(seeded.get("zyxq").get(0), foreign, already, moneyIn, UUID.randomUUID());

        JsonNode r = api.postJson("/api/v1/transactions/uncategorized/apply", applyBody(ids, "food", "food.delivery", null), 200);

        assertEquals(1, r.get("updated").asInt());
        assertEquals(4, r.get("skipped").asInt());
        // the foreign row is untouched, the categorised one keeps its own pick, the credit rejects an out-category
        assertTrue(other.getJson("/api/v1/transactions/" + foreign, 200).get("category").isNull());
        assertEquals("food", api.getJson("/api/v1/transactions/" + already, 200).get("category").get("key").asText());
        assertTrue(api.getJson("/api/v1/transactions/" + moneyIn, 200).get("category").isNull());
    }

    @Test
    void applyValidatesTheCategoryAndTheBody() throws Exception {
        UUID id = out("ZYXQ TRADERS", 10, "2026-10-01");
        assertEquals(400, api.post("/api/v1/transactions/uncategorized/apply", applyBody(List.of(id), "nope", null, null)).getStatus());
        assertEquals(400, api.post("/api/v1/transactions/uncategorized/apply",
                applyBody(List.of(id), "food", "groceries.supermarket", null)).getStatus());
        assertEquals(400, api.post("/api/v1/transactions/uncategorized/apply", applyBody(List.of(), "food", null, null)).getStatus());
        assertEquals(400, api.post("/api/v1/transactions/uncategorized/apply",
                applyBody(List.of(id), "food", null, Map.of("pattern", "zyxq", "matchType", "SIDEWAYS"))).getStatus());
        assertTrue(api.getJson("/api/v1/transactions/" + id, 200).get("category").isNull(), "a rejected call changes nothing");
    }

    @Test
    void applyWithARuleCreatesItOnceAndNeverTouchesPastMatches() throws Exception {
        Map<String, List<UUID>> seeded = seed();
        UUID later = out("ZYXQ TRADERS AGAIN", 5, "2026-10-20");

        JsonNode r = api.postJson("/api/v1/transactions/uncategorized/apply",
                applyBody(seeded.get("zyxq"), "shopping", null, Map.of("pattern", "zyxq traders")), 200);

        assertEquals(3, r.get("updated").asInt());
        String ruleId = r.get("ruleId").asText();
        JsonNode rules = api.getJson("/api/v1/rules?size=50", 200);
        assertEquals(1, rules.get("totalElements").asInt());
        JsonNode rule = rules.get("content").get(0);
        assertEquals(ruleId, rule.get("id").asText());
        assertEquals("CONTAINS", rule.get("matchType").asText());
        assertEquals("shopping", rule.get("category").get("key").asText());
        // saving the rule is not "update past matches": the other matching row stays uncategorised
        assertTrue(api.getJson("/api/v1/transactions/" + later, 200).get("category").isNull());
        assertTrue(api.getJson("/api/v1/transactions/" + seeded.get("oneoffs").get(2), 200).get("category").isNull());
    }

    @Test
    void applyWithTheSameRuleAgainRepointsItsCategoryInsteadOfDuplicating() throws Exception {
        Map<String, List<UUID>> seeded = seed();
        Map<String, Object> rule = Map.of("pattern", "wvut traders", "matchType", "contains");
        String first = api.postJson("/api/v1/transactions/uncategorized/apply",
                applyBody(seeded.get("zyxq"), "shopping", null, rule), 200).get("ruleId").asText();
        String second = api.postJson("/api/v1/transactions/uncategorized/apply",
                applyBody(seeded.get("wvut"), "groceries", "groceries.supermarket", rule), 200).get("ruleId").asText();

        assertEquals(first, second);
        JsonNode rules = api.getJson("/api/v1/rules?size=50", 200);
        assertEquals(1, rules.get("totalElements").asInt());
        assertEquals("groceries", rules.get("content").get(0).get("category").get("key").asText());
        assertEquals("groceries.supermarket", rules.get("content").get(0).get("subcategory").get("key").asText());

        // another match type is another rule
        api.postJson("/api/v1/transactions/uncategorized/apply",
                applyBody(seeded.get("ravi"), "family", null, Map.of("pattern", "wvut traders", "matchType", "EXACT")), 200);
        assertEquals(2, api.getJson("/api/v1/rules?size=50", 200).get("totalElements").asInt());
    }

    @Test
    void applyIgnoresAnotherUsersRulesAndRowsForTheRule() throws Exception {
        UUID foreign = txn(other, otherBank, -10, "THEIRS TRADERS", "2026-10-01");
        other.postJson("/api/v1/transactions/uncategorized/apply",
                applyBody(List.of(txn(other, otherBank, -10, "WVUT TRADERS", "2026-10-02")), "food", null,
                        Map.of("pattern", "wvut traders")), 200);

        JsonNode r = api.postJson("/api/v1/transactions/uncategorized/apply",
                applyBody(List.of(foreign), "shopping", null, Map.of("pattern", "wvut traders")), 200);

        assertEquals(0, r.get("updated").asInt());
        assertEquals(1, r.get("skipped").asInt());
        assertEquals(1, api.getJson("/api/v1/rules", 200).get("totalElements").asInt(), "my own new rule, not theirs");
        assertEquals("food", other.getJson("/api/v1/rules", 200).get("content").get(0).get("category").get("key").asText());
    }

    // ------------------------------------------------------------------ category-recent

    @Test
    void recentPicksAreDistinctNewestFirstCappedAtFourAndPerDirection() throws Exception {
        String[][] picks = {{"food", "food.dining"}, {"groceries", null}, {"transport", "transport.cabs"},
                {"shopping", null}, {"bills", "bills.rent"}};
        List<UUID> picked = new ArrayList<>();
        for (int i = 0; i < picks.length; i++) {
            UUID id = out("ZYXQ " + i + " TRADERS", 10, "2026-10-01");
            pick(api, id, picks[i][0], picks[i][1]);
            picked.add(id);
            jdbc.update("UPDATE transactions SET category_set_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(java.time.Instant.parse("2026-10-01T00:00:00Z").plusSeconds(i * 60L)), id.toString());
        }
        // the same pair again (older than all): still one entry
        UUID dup = out("ZYXQ DUP TRADERS", 10, "2026-10-01");
        pick(api, dup, "food", "food.dining");
        jdbc.update("UPDATE transactions SET category_set_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2025-01-01T00:00:00Z")), dup.toString());
        UUID salary = in("SALARY NONSENSE", 100, "2026-10-02");
        pick(api, salary, "salary", "salary.salary");

        JsonNode outPicks = api.getJson("/api/v1/transactions/category-recent?direction=OUT", 200);

        assertEquals(4, outPicks.size());
        assertEquals("bills.rent", outPicks.get(0).get("subcategoryKey").asText());
        assertEquals("bills", outPicks.get(0).get("categoryKey").asText());
        assertEquals("shopping", outPicks.get(1).get("categoryKey").asText());
        assertTrue(outPicks.get(1).get("subcategoryKey").isNull());
        assertEquals("transport.cabs", outPicks.get(2).get("subcategoryKey").asText());
        assertEquals("groceries", outPicks.get(3).get("categoryKey").asText());

        JsonNode inPicks = api.getJson("/api/v1/transactions/category-recent?direction=in", 200);
        assertEquals(1, inPicks.size());
        assertEquals("salary", inPicks.get(0).get("categoryKey").asText());
    }

    @Test
    void recentPicksIgnoreRuleAndAutoCategoriesAndOtherUsers() throws Exception {
        out("SWIGGY", 100, "2026-10-01"); // categorised by the engine on arrival, not by the user
        JsonNode t = api.getJson("/api/v1/transactions/category-recent?direction=OUT", 200);
        assertEquals(0, t.size());

        UUID theirs = txn(other, otherBank, -10, "ZYXQ TRADERS", "2026-10-01");
        pick(other, theirs, "pets", null);
        assertEquals(0, api.getJson("/api/v1/transactions/category-recent?direction=OUT", 200).size());
        assertEquals(1, other.getJson("/api/v1/transactions/category-recent?direction=OUT", 200).size());
    }

    @Test
    void recentPicksNeedAValidDirection() throws Exception {
        assertEquals(400, api.get("/api/v1/transactions/category-recent?direction=SIDEWAYS").getStatus());
        assertEquals(400, api.get("/api/v1/transactions/category-recent").getStatus());
    }

    // ------------------------------------------------------------------ category-counts

    @Test
    void categoryCountsAreWholeLedgerPerCategoryWithSubsAndUncategorised() throws Exception {
        pick(api, out("ZYXQ A TRADERS", 1, "2026-10-01"), "food", "food.dining");
        pick(api, out("ZYXQ B TRADERS", 1, "2026-10-01"), "food", "food.dining");
        pick(api, out("ZYXQ C TRADERS", 1, "2026-10-01"), "food", "food.delivery");
        pick(api, out("ZYXQ D TRADERS", 1, "2026-10-01"), "food", null);
        pick(api, out("ZYXQ E TRADERS", 1, "2026-10-01"), "savings", null);
        out("ZYXQ F TRADERS", 1, "2026-10-01");
        txn(other, otherBank, -1, "THEIRS TRADERS", "2026-10-01");

        JsonNode c = api.getJson("/api/v1/transactions/category-counts", 200);

        assertEquals(1, c.get("uncategorized").asInt());
        JsonNode cats = c.get("categories");
        assertEquals(2, cats.size());
        assertEquals("food", cats.get(0).get("categoryKey").asText());
        assertEquals(4, cats.get(0).get("count").asInt(), "a category counts its subs and its own rows");
        JsonNode subs = cats.get(0).get("subs");
        assertEquals(2, subs.size());
        assertEquals("food.dining", subs.get(0).get("subcategoryKey").asText());
        assertEquals(2, subs.get(0).get("count").asInt());
        assertEquals("savings", cats.get(1).get("categoryKey").asText());
        assertEquals(0, cats.get(1).get("subs").size());
        assertEquals(1, other.getJson("/api/v1/transactions/category-counts", 200).get("uncategorized").asInt());
    }

    @Test
    void categoryCountsOfAnEmptyLedger() throws Exception {
        JsonNode c = api.getJson("/api/v1/transactions/category-counts", 200);
        assertEquals(0, c.get("uncategorized").asInt());
        assertEquals(0, c.get("categories").size());
    }

    // ------------------------------------------------------------------ list filters

    @Test
    void categoryFilterTakesKeysAndAKeyIncludesItsSubs() throws Exception {
        UUID dining = out("ZYXQ A TRADERS", 1, "2026-10-01");
        UUID delivery = out("ZYXQ B TRADERS", 1, "2026-10-01");
        UUID plainFood = out("ZYXQ C TRADERS", 1, "2026-10-01");
        UUID groceries = out("ZYXQ D TRADERS", 1, "2026-10-01");
        UUID none = out("ZYXQ E TRADERS", 1, "2026-10-01");
        pick(api, dining, "food", "food.dining");
        pick(api, delivery, "food", "food.delivery");
        pick(api, plainFood, "food", null);
        pick(api, groceries, "groceries", null);

        JsonNode food = search(List.of(filter("category", "is", "food")));
        assertEquals(3, food.get("totalElements").asInt());
        assertEquals(List.of(dining, delivery, plainFood).stream().map(UUID::toString).sorted().toList(),
                ids(food).stream().sorted().toList());

        assertEquals(4, search(List.of(filter("category", "in", List.of("food", "groceries")))).get("totalElements").asInt());
        assertEquals(2, search(List.of(filter("category", "is_not", "food"))).get("totalElements").asInt(),
                "not food includes the uncategorised row");
        assertEquals(1, search(List.of(filter("category", "not_in", List.of("food", "groceries")))).get("totalElements").asInt());
        assertEquals(1, search(List.of(filter("category", "is_empty", null))).get("totalElements").asInt());
        assertEquals(List.of(none.toString()), ids(search(List.of(filter("category", "is_empty", null)))));
    }

    @Test
    void subcategoryFilterTakesSubKeys() throws Exception {
        UUID dining = out("ZYXQ A TRADERS", 1, "2026-10-01");
        UUID delivery = out("ZYXQ B TRADERS", 1, "2026-10-01");
        UUID plainFood = out("ZYXQ C TRADERS", 1, "2026-10-01");
        pick(api, dining, "food", "food.dining");
        pick(api, delivery, "food", "food.delivery");
        pick(api, plainFood, "food", null);

        assertEquals(List.of(dining.toString()), ids(search(List.of(filter("subcategory", "is", "food.dining")))));
        assertEquals(2, search(List.of(filter("subcategory", "in", List.of("food.dining", "food.delivery")))).get("totalElements").asInt());
        // combined with the category it narrows the same way
        assertEquals(1, search(List.of(filter("category", "is", "food"), filter("subcategory", "is", "food.delivery")))
                .get("totalElements").asInt());
    }

    @Test
    void uncategorizedFlagFiltersOnNoCategory() throws Exception {
        UUID taken = out("ZYXQ A TRADERS", 1, "2026-10-01");
        UUID bare = out("ZYXQ B TRADERS", 1, "2026-10-01");
        pick(api, taken, "food", null);

        assertEquals(List.of(bare.toString()), ids(search(List.of(filter("uncategorized", "is", true)))));
        assertEquals(List.of(taken.toString()), ids(search(List.of(filter("uncategorized", "is", false)))));
        assertEquals(1, api.postJson("/api/v1/transactions/count",
                Map.of("filters", List.of(filter("uncategorized", "is", true))), 200).get("count").asInt());
    }

    @Test
    void tagFilterTakesTagIdsAndUntaggedFindsTheRest() throws Exception {
        UUID tagA = api.create("/api/v1/tags", Map.of("name", "Trip"));
        UUID tagB = api.create("/api/v1/tags", Map.of("name", "Work"));
        UUID withA = tagged("ZYXQ A TRADERS", List.of(tagA));
        tagged("ZYXQ B TRADERS", List.of(tagB));
        UUID withBoth = tagged("ZYXQ C TRADERS", List.of(tagA, tagB));
        UUID bare = out("ZYXQ D TRADERS", 1, "2026-10-01");

        assertEquals(List.of(withA, withBoth).stream().map(UUID::toString).sorted().toList(),
                ids(search(List.of(filter("tag", "is", tagA.toString())))).stream().sorted().toList());
        assertEquals(3, search(List.of(filter("tag", "in", List.of(tagA.toString(), tagB.toString())))).get("totalElements").asInt());
        assertEquals(2, search(List.of(filter("tag", "not_in", List.of(tagA.toString())))).get("totalElements").asInt());
        assertEquals(List.of(bare.toString()), ids(search(List.of(filter("untagged", "is", true)))));
        assertEquals(3, search(List.of(filter("untagged", "is", false))).get("totalElements").asInt());
    }

    private UUID tagged(String description, List<UUID> tags) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", bank.toString());
        body.put("date", "2026-10-01");
        body.put("amount", -1);
        body.put("description", description);
        body.put("tagIds", tags.stream().map(UUID::toString).toList());
        return api.create("/api/v1/transactions", body);
    }

    @Test
    void filtersNeverReachAnotherUsersRows() throws Exception {
        UUID theirs = txn(other, otherBank, -1, "THEIRS TRADERS", "2026-10-01");
        pick(other, theirs, "food", null);
        out("ZYXQ A TRADERS", 1, "2026-10-01");

        assertEquals(0, search(List.of(filter("category", "is", "food"))).get("totalElements").asInt());
        assertEquals(1, search(List.of(filter("uncategorized", "is", true))).get("totalElements").asInt());
    }

    @Test
    void newFlagFieldsRejectABadOperatorOrValue() throws Exception {
        for (String field : List.of("uncategorized", "untagged")) {
            assertEquals(400, api.post("/api/v1/transactions/search",
                    Map.of("filters", List.of(filter(field, "is_not", true)))).getStatus());
            assertEquals(400, api.post("/api/v1/transactions/search",
                    Map.of("filters", List.of(filter(field, "is", "yes")))).getStatus());
        }
    }

    // ------------------------------------------------------------------ OR within a dimension, AND across

    private Map<String, UUID> orSeed() throws Exception {
        Map<String, UUID> m = new LinkedHashMap<>();
        m.put("delivery", out("ZYXQ A TRADERS", 1, "2026-10-01"));
        m.put("dining", out("ZYXQ B TRADERS", 1, "2026-10-01"));
        m.put("groceries", out("ZYXQ C TRADERS", 1, "2026-10-01"));
        m.put("cabs", out("ZYXQ D TRADERS", 1, "2026-10-01"));
        m.put("shopping", out("ZYXQ E TRADERS", 1, "2026-10-01"));
        m.put("none", out("ZYXQ F TRADERS", 1, "2026-10-01"));
        pick(api, m.get("delivery"), "food", "food.delivery");
        pick(api, m.get("dining"), "food", "food.dining");
        pick(api, m.get("groceries"), "groceries", null);
        pick(api, m.get("cabs"), "transport", "transport.cabs");
        pick(api, m.get("shopping"), "shopping", null);
        return m;
    }

    private List<String> idsOf(Map<String, UUID> m, String... names) {
        return java.util.Arrays.stream(names).map(n -> m.get(n).toString()).sorted().toList();
    }

    private List<String> found(List<Map<String, Object>> filters) throws Exception {
        return ids(search(filters)).stream().sorted().toList();
    }

    @Test
    void uncategorizedOrCategoryWidens() throws Exception {
        Map<String, UUID> m = orSeed();
        assertEquals(idsOf(m, "none", "groceries"),
                found(List.of(filter("uncategorized", "is", true), filter("category", "is", "groceries"))));
        assertEquals(idsOf(m, "none", "groceries", "shopping"),
                found(List.of(filter("uncategorized", "is", true), filter("category", "in", List.of("groceries", "shopping")))));
    }

    @Test
    void categoryOrAnotherCategorysSubWidens() throws Exception {
        Map<String, UUID> m = orSeed();
        assertEquals(idsOf(m, "groceries", "cabs"),
                found(List.of(filter("category", "is", "groceries"), filter("subcategory", "is", "transport.cabs"))));
    }

    @Test
    void aSubOfAChosenCategoryNarrowsIt() throws Exception {
        Map<String, UUID> m = orSeed();
        assertEquals(idsOf(m, "delivery"),
                found(List.of(filter("category", "is", "food"), filter("subcategory", "is", "food.delivery"))));
        // narrowing one category leaves the other whole, and other sub still widens
        assertEquals(idsOf(m, "delivery", "groceries", "cabs"),
                found(List.of(filter("category", "in", List.of("food", "groceries")),
                        filter("subcategory", "in", List.of("food.delivery", "transport.cabs")))));
    }

    @Test
    void untaggedOrTagWidens() throws Exception {
        UUID tagA = api.create("/api/v1/tags", Map.of("name", "Trip"));
        UUID tagB = api.create("/api/v1/tags", Map.of("name", "Work"));
        UUID withA = tagged("ZYXQ A TRADERS", List.of(tagA));
        tagged("ZYXQ B TRADERS", List.of(tagB));
        UUID bare = out("ZYXQ C TRADERS", 1, "2026-10-01");

        assertEquals(List.of(withA, bare).stream().map(UUID::toString).sorted().toList(),
                found(List.of(filter("untagged", "is", true), filter("tag", "in", List.of(tagA.toString())))));
        // two tag clauses are OR as well
        UUID withB = jdbc.queryForObject("SELECT id FROM transactions WHERE user_id = (SELECT user_id FROM transactions WHERE id = ?)"
                + " AND description = 'ZYXQ B TRADERS'", UUID.class, withA.toString());
        assertEquals(List.of(withA, withB).stream().map(UUID::toString).sorted().toList(),
                found(List.of(filter("tag", "is", tagA.toString()), filter("tag", "is", tagB.toString()))));
    }

    @Test
    void categoryAndTagStillAnd() throws Exception {
        UUID tagA = api.create("/api/v1/tags", Map.of("name", "Trip"));
        UUID foodTagged = tagged("ZYXQ A TRADERS", List.of(tagA));
        UUID groceriesTagged = tagged("ZYXQ B TRADERS", List.of(tagA));
        UUID foodBare = out("ZYXQ C TRADERS", 1, "2026-10-01");
        pick(api, foodTagged, "food", null);
        pick(api, groceriesTagged, "groceries", null);
        pick(api, foodBare, "food", null);

        assertEquals(List.of(foodTagged.toString()),
                found(List.of(filter("category", "is", "food"), filter("tag", "is", tagA.toString()))));
        // OR inside each dimension, AND between them
        assertEquals(List.of(foodTagged, groceriesTagged, foodBare).stream().map(UUID::toString).sorted().toList(),
                found(List.of(filter("category", "in", List.of("food", "groceries")), filter("untagged", "is", true),
                        filter("tag", "is", tagA.toString()))));
        assertEquals(List.of(foodTagged, groceriesTagged).stream().map(UUID::toString).sorted().toList(),
                found(List.of(filter("category", "in", List.of("food", "groceries")), filter("tag", "is", tagA.toString()))));
    }

    @Test
    void orGroupsStayScopedToTheUser() throws Exception {
        orSeed();
        UUID theirs = txn(other, otherBank, -1, "THEIRS TRADERS", "2026-10-01");
        pick(other, theirs, "food", null);
        txn(other, otherBank, -1, "THEIRS BARE TRADERS", "2026-10-01");
        assertEquals(3, search(List.of(filter("uncategorized", "is", true), filter("category", "is", "food")))
                .get("totalElements").asInt(), "mine only: none + delivery + dining");
    }
}
