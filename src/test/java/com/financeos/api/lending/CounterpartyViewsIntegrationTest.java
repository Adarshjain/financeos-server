package com.financeos.api.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GET /counterparties: direction filter, /{id}, /summary, /{id}/ledger and /match-suggestions against
 * real persistence, including that the literal paths do not collide with /{id}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CounterpartyViewsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private ApiTestClient other;
    private UUID userId;
    private UUID otherId;
    private UUID asha;
    private UUID bala;
    private UUID zed;
    private LocalDate monthEnd;

    @BeforeEach
    void setUp() throws Exception {
        String email = "cp-views-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "cp-views-other-" + UUID.randomUUID() + "@example.test";
        other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherId = userRepository.findByEmail(otherEmail).orElseThrow().getId();

        LocalDate today = AppTime.today();
        monthEnd = today.withDayOfMonth(today.lengthOfMonth());

        asha = person(api, "Asha");                          // owes you 700, expected on the last day of the month
        entry(api, asha, "lent", "principal", "1000", "2026-01-10", monthEnd.toString());
        entry(api, asha, "borrowed", "settlement", "300", "2026-01-15", null);
        entry(api, asha, "lent", "settlement", "0.01", "2026-01-15", null);
        bala = person(api, "Bala");                          // you owe 400, expected the 1st of next month
        entry(api, bala, "borrowed", "principal", "400", "2026-01-10", monthEnd.plusDays(1).toString());
        UUID chirag = person(api, "Chirag");                 // settled
        entry(api, chirag, "lent", "principal", "50", "2026-01-10", null);
        entry(api, chirag, "borrowed", "settlement", "50", "2026-01-11", null);
        zed = person(other, "Zed");
        entry(other, zed, "lent", "principal", "99999", "2026-01-10", monthEnd.toString());
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherId));
    }

    private static UUID person(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/counterparties", Map.of("name", name));
    }

    private static void entry(ApiTestClient client, UUID cp, String direction, String kind, String amount,
                              String date, String expected) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("counterpartyId", cp.toString(), "direction", direction,
                "kind", kind, "amount", amount, "entryDate", date));
        if (expected != null) body.put("expectedReturnDate", expected);
        client.postJson("/api/v1/lendings", body, 200);
    }

    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("content").forEach(n -> names.add(n.get("name").asText()));
        return names;
    }

    @Test
    void directionFiltersListAndTotals() throws Exception {
        JsonNode owed = api.getJson("/api/v1/counterparties?direction=OWED_TO_YOU", 200);
        assertEquals(List.of("Asha"), names(owed));
        assertEquals(1, owed.get("totalElements").asInt());
        assertEquals(List.of("Bala"), names(api.getJson("/api/v1/counterparties?direction=YOU_OWE", 200)));
        assertEquals(List.of("Chirag"), names(api.getJson("/api/v1/counterparties?direction=SETTLED", 200)));
        assertEquals(3, api.getJson("/api/v1/counterparties", 200).get("totalElements").asInt());
    }

    @Test
    void directionPagesAfterTheFilterAndKeepsQAndSort() throws Exception {
        entry(api, person(api, "Dev"), "lent", "principal", "5", "2026-01-10", null);
        JsonNode page = api.getJson("/api/v1/counterparties?direction=OWED_TO_YOU&sort=net&page=1&size=1", 200);
        assertEquals(List.of("Dev"), names(page));
        assertEquals(2, page.get("totalElements").asInt());
        assertEquals(List.of("Dev"), names(api.getJson("/api/v1/counterparties?direction=OWED_TO_YOU&q=de", 200)));
        api.getJson("/api/v1/counterparties?direction=BOGUS", 400);
    }

    @Test
    void detailCarriesLastEntryAndExpectedReturn() throws Exception {
        JsonNode a = api.getJson("/api/v1/counterparties/" + asha, 200);
        assertEquals("Asha", a.get("name").asText());
        assertEquals(700.01, a.get("netPosition").asDouble(), 0.0001);
        assertEquals("2026-01-15", a.get("lastEntryDate").asText());
        assertEquals("lent", a.get("lastEntryType").asText());
        assertEquals("settlement", a.get("lastEntryKind").asText());
        assertEquals(0.01, a.get("lastEntryAmount").asDouble(), 0.0001);
        assertEquals(monthEnd.toString(), a.get("expectedReturnDate").asText());
        assertTrue(api.getJson("/api/v1/counterparties/" + zed, 404) != null);
        api.getJson("/api/v1/counterparties/" + UUID.randomUUID(), 404);
        // the list carries the same fields
        JsonNode first = api.getJson("/api/v1/counterparties?direction=OWED_TO_YOU", 200).get("content").get(0);
        assertEquals(monthEnd.toString(), first.get("expectedReturnDate").asText());
        assertTrue(api.getJson("/api/v1/counterparties?direction=SETTLED", 200).get("content").get(0)
                .get("expectedReturnDate").isNull());
    }

    @Test
    void firstEntryDateAndLinkedCountPerPersonAndTenantScoped() throws Exception {
        UUID empty = person(api, "Empty");
        JsonNode a = api.getJson("/api/v1/counterparties/" + asha, 200);
        assertEquals("2026-01-10", a.get("firstEntryDate").asText());
        assertEquals(3, a.get("entryCount").asInt());
        assertEquals(0, a.get("linkedEntryCount").asInt());
        JsonNode e = api.getJson("/api/v1/counterparties/" + empty, 200);
        assertTrue(e.get("firstEntryDate").isNull());
        assertEquals(0, e.get("linkedEntryCount").asInt());
        for (JsonNode n : api.getJson("/api/v1/counterparties", 200).get("content")) {
            if (n.get("name").asText().equals("Chirag")) {
                assertEquals("2026-01-10", n.get("firstEntryDate").asText());
                assertEquals(2, n.get("entryCount").asInt());
            }
            if (n.get("name").asText().equals("Empty")) assertTrue(n.get("firstEntryDate").isNull());
        }
        JsonNode z = other.getJson("/api/v1/counterparties/" + zed, 200);
        assertEquals(1, z.get("entryCount").asInt());
    }

    @Test
    void summaryCountsTheLastDayOfTheMonthButNotTheNext() throws Exception {
        JsonNode s = api.getJson("/api/v1/counterparties/summary", 200);

        assertEquals(700.01, s.get("owedToYou").asDouble(), 0.0001);
        assertEquals(1, s.get("owedToYouCount").asInt());
        assertEquals(400, s.get("youOwe").asDouble(), 0.0001);
        assertEquals(1, s.get("youOweCount").asInt());
        assertEquals(1, s.get("expectedBack").get("count").asInt());
        assertEquals("Asha", s.get("expectedBack").get("nextName").asText());
        assertEquals(monthEnd.toString(), s.get("expectedBack").get("nextDate").asText());
        assertEquals(0, s.get("dueThisMonth").get("count").asInt());
        assertEquals(0, s.get("dueThisMonth").get("amount").asDouble(), 0.0001);
        assertTrue(s.get("dueThisMonth").get("nextName").isNull());
        assertTrue(s.get("dueThisMonth").get("nextDate").isNull());
    }

    @Test
    void ledgerListsNewestFirstWithRunningBalanceAcrossPages() throws Exception {
        JsonNode first = api.getJson("/api/v1/counterparties/" + asha + "/ledger?page=0&size=2", 200);
        JsonNode second = api.getJson("/api/v1/counterparties/" + asha + "/ledger?page=1&size=2", 200);

        JsonNode p0 = first.get("entries");
        assertEquals(3, p0.get("totalElements").asInt());
        // 15 Jan has two entries: the later-created (lent 0.01) is newest
        assertEquals(700.01, p0.get("content").get(0).get("runningBalance").asDouble(), 0.0001);
        assertEquals(0.01, p0.get("content").get(0).get("amount").asDouble(), 0.0001);
        assertEquals(700, p0.get("content").get(1).get("runningBalance").asDouble(), 0.0001);
        assertEquals("Asha", p0.get("content").get(0).get("counterpartyName").asText());
        JsonNode p1 = second.get("entries");
        assertEquals(1, p1.get("content").size());
        assertEquals(1000, p1.get("content").get(0).get("runningBalance").asDouble(), 0.0001);

        JsonNode totals = first.get("totals");
        assertEquals(1000.01, totals.get("lent").asDouble(), 0.0001);
        assertEquals(300, totals.get("received").asDouble(), 0.0001);
        assertEquals(700.01, totals.get("closing").asDouble(), 0.0001);
        assertEquals(totals.get("closing").asDouble(),
                api.getJson("/api/v1/counterparties/" + asha, 200).get("netPosition").asDouble(), 0.0001);
    }

    @Test
    void ledgerIs404ForForeignAndMissing() throws Exception {
        api.getJson("/api/v1/counterparties/" + zed + "/ledger", 404);
        api.getJson("/api/v1/counterparties/" + UUID.randomUUID() + "/ledger", 404);
    }

    @Test
    void matchSuggestionsRouteReturnsAnEmptyListWhenNothingMatches() throws Exception {
        JsonNode all = api.getJson("/api/v1/counterparties/match-suggestions", 200);
        assertTrue(all.isArray());
        assertEquals(0, all.size());
    }

    @Test
    void matchSuggestionsListsAPersonWithACandidateTransaction() throws Exception {
        LocalDate day = AppTime.today().minusDays(1);
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Match Bank", "last4", "4321",
                "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));
        Map<String, Object> debit = new HashMap<>();
        debit.put("accountId", bank.toString());
        debit.put("date", day.toString());
        debit.put("amount", -777);
        debit.put("description", "Transfer to Dev");
        UUID txn = api.create("/api/v1/transactions", debit);
        UUID dev = person(api, "Dev");
        entry(api, dev, "lent", "principal", "777", day.toString(), null);

        JsonNode all = api.getJson("/api/v1/counterparties/match-suggestions", 200);

        assertEquals(1, all.size());
        assertEquals(dev.toString(), all.get(0).get("counterpartyId").asText());
        assertEquals("Dev", all.get(0).get("counterpartyName").asText());
        JsonNode suggestion = all.get(0).get("suggestions").get(0);
        assertEquals(txn.toString(), suggestion.get("candidates").get(0).get("id").asText());
    }
}
