package com.financeos.api.search;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /api/v1/search over real persistence: matching columns, caps, ordering, escaping, validation, tenancy. */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalSearchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;

    private ApiTestClient api;
    private ApiTestClient other;
    private UUID userId;
    private UUID otherUserId;
    private UUID bank;
    private final List<UUID> instrumentIds = new ArrayList<>();
    private final String tag = UUID.randomUUID().toString().substring(0, 8).replace("-", "x");

    @BeforeEach
    void setUp() throws Exception {
        String email = "search-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "search-other-" + UUID.randomUUID() + "@example.test";
        other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        bank = account(api, "Zeta Savings " + tag, null);
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
        UserDataCleanup.deleteInstruments(jdbc, instrumentIds);
    }

    // ---------- helpers ----------

    private static UUID account(ApiTestClient c, String name, String bankCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("type", "bank_account", "name", name, "last4", "1234",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
        if (bankCode != null) {
            body.put("bank", bankCode);
        }
        return c.create("/api/v1/accounts", body);
    }

    private UUID creditCard(ApiTestClient c, String name, String last4) throws Exception {
        return c.create("/api/v1/accounts", Map.of("type", "credit_card", "name", name, "last4", last4,
                "creditLimit", 200000, "anniversaryDate", "2026-01-15", "financialPosition", "liability",
                "excludeFromNetAsset", false));
    }

    private UUID txn(ApiTestClient c, UUID account, String description, double amount, int daysAgo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", account.toString());
        body.put("date", AppTime.today().minusDays(daysAgo).toString());
        body.put("amount", amount);
        body.put("description", description);
        return c.create("/api/v1/transactions", body);
    }

    private UUID loan(ApiTestClient c, String name, String lender, String number) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("loanType", "personal");
        body.put("lender", lender);
        body.put("loanAccountNumber", number);
        body.put("principal", "100000");
        body.put("annualRatePct", "12");
        body.put("rateType", "fixed");
        body.put("tenureMonths", 12);
        body.put("startDate", "2026-01-01");
        body.put("firstEmiDate", "2026-02-01");
        return c.create("/api/v1/loans", body);
    }

    private UUID report(UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reports (id, user_id, name, type, datasource, definition) VALUES (?, ?, ?, 'KPI', 'transactions', '{}')",
                id.toString(), owner.toString(), name);
        return id;
    }

    private UUID dashboard(UUID owner, String name, boolean isDefault) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO dashboards (id, user_id, name, widgets, is_default) VALUES (?, ?, ?, '[]', ?)",
                id.toString(), owner.toString(), name, isDefault ? 1 : 0);
        return id;
    }

    private UUID counterparty(UUID owner, String name) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO counterparties (id, user_id, name, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                id.toString(), owner.toString(), name, now, now);
        return id;
    }

    private UUID instrument(String name, String symbol) {
        UUID id = UUID.randomUUID();
        instrumentIds.add(id);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO instruments (id, type, name, symbol, currency, created_at, updated_at) VALUES (?, 'stock', ?, ?, 'INR', ?, ?)",
                id.toString(), name, symbol, now, now);
        return id;
    }

    private void hold(UUID owner, UUID brokerAccount, UUID instrument) {
        jdbc.update("INSERT INTO holdings (id, user_id, broker_account_id, instrument_id, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), owner.toString(), brokerAccount.toString(), instrument.toString(),
                Timestamp.from(Instant.now()));
    }

    private void override(UUID owner, UUID instrument, String name, String symbol) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO user_instrument_overrides (id, user_id, instrument_id, name, symbol, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), owner.toString(), instrument.toString(), name, symbol, now, now);
    }

    private UUID broker(ApiTestClient c, String name) throws Exception {
        return c.create("/api/v1/accounts", Map.of("type", "broker", "name", name, "provider", "zerodha",
                "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private JsonNode search(ApiTestClient c, String q) throws Exception {
        return c.getJson("/api/v1/search?q=" + q, 200);
    }

    private static List<String> texts(JsonNode group, String field) {
        List<String> out = new ArrayList<>();
        group.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    // ---------- groups and columns ----------

    @Test
    void accountMatchesByNameBankAndCardLast4() throws Exception {
        UUID hdfc = account(api, "Primary " + tag, "hdfc");
        UUID card = creditCard(api, "Regalia " + tag, "7741");

        JsonNode byName = search(api, "regalia " + tag).get("accounts");
        assertEquals(1, byName.size());
        assertEquals(card.toString(), byName.get(0).get("id").asText());
        assertEquals("credit_card", byName.get(0).get("type").asText());
        assertEquals("7741", byName.get(0).get("last4").asText(), "first card's last4 when matched by name");

        JsonNode byBank = search(api, "hdfc").get("accounts");
        assertEquals(List.of(hdfc.toString()), texts(byBank, "id"));
        assertEquals("hdfc", byBank.get(0).get("bank").asText());
        assertTrue(byBank.get(0).get("last4").isNull(), "no card -> null last4");

        JsonNode byLast4 = search(api, "7741").get("accounts");
        assertEquals(List.of(card.toString()), texts(byLast4, "id"));
        assertEquals("7741", byLast4.get(0).get("last4").asText());
    }

    @Test
    void accountLast4IsTheMatchingCardsWhenSeveralCardsExist() throws Exception {
        UUID card = creditCard(api, "Multi " + tag, "1111");
        JsonNode holder = api.getJson("/api/v1/accounts/" + card + "/cardholders", 200).get(0);
        UUID oldCard = UUID.fromString(holder.get("cards").get(0).get("id").asText());
        api.postJson("/api/v1/accounts/" + card + "/cardholders/" + holder.get("id").asText() + "/cards/" + oldCard + "/replace",
                Map.of("newLast4", "2222", "issuedOn", "2026-05-01"), 200);

        assertEquals("2222", search(api, "2222").get("accounts").get(0).get("last4").asText());
        assertEquals("1111", search(api, "1111").get("accounts").get(0).get("last4").asText());
    }

    @Test
    void transactionMatchesByDescriptionSourcedDescriptionAndAmountWithSignedAmount() throws Exception {
        UUID debit = txn(api, bank, "Croma Electronics " + tag, -24990.00, 3);
        UUID credit = txn(api, bank, "Refund unique" + tag, 1500.50, 2);
        UUID sourced = txn(api, bank, "Plain note", -10, 1);
        jdbc.update("UPDATE transactions SET sourced_description = ? WHERE id = ?", "UPI/RAW/zzsrc" + tag, sourced.toString());

        JsonNode byDescription = search(api, "croma electronics " + tag).get("transactions");
        assertEquals(1, byDescription.size());
        JsonNode hit = byDescription.get(0);
        assertEquals(debit.toString(), hit.get("id").asText());
        assertEquals(AppTime.today().minusDays(3).toString(), hit.get("date").asText());
        assertEquals(bank.toString(), hit.get("accountId").asText());
        assertEquals("Zeta Savings " + tag, hit.get("accountName").asText());
        assertEquals(0, hit.get("amount").decimalValue().compareTo(new java.math.BigDecimal("-24990.00")));

        JsonNode creditHit = search(api, "unique" + tag).get("transactions").get(0);
        assertEquals(credit.toString(), creditHit.get("id").asText());
        assertEquals(0, creditHit.get("amount").decimalValue().compareTo(new java.math.BigDecimal("1500.50")));

        assertEquals(List.of(sourced.toString()), texts(search(api, "zzsrc" + tag).get("transactions"), "id"));
        assertEquals(List.of(debit.toString()), texts(search(api, "24,990").get("transactions"), "id"));
        assertEquals(List.of(credit.toString()), texts(search(api, "1500.5").get("transactions"), "id"));
    }

    @Test
    void transactionsDoNotMatchAccountOrCategoryNames() throws Exception {
        UUID category = api.create("/api/v1/categories", Map.of("name", "Catname" + tag));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", bank.toString());
        body.put("date", AppTime.today().toString());
        body.put("amount", -5);
        body.put("description", "Unrelated words");
        body.put("categoryIds", List.of(category.toString()));
        api.create("/api/v1/transactions", body);

        JsonNode byAccount = search(api, "zeta savings " + tag);
        assertEquals(1, byAccount.get("accounts").size());
        assertEquals(0, byAccount.get("transactions").size());
        assertEquals(0, search(api, "catname" + tag).get("transactions").size());
    }

    @Test
    void holdingMatchesByNameSymbolAndOverrideAndIsOneRowPerInstrument() throws Exception {
        UUID broker1 = broker(api, "Broker One " + tag);
        UUID broker2 = broker(api, "Broker Two " + tag);
        UUID infy = instrument("Infosys " + tag, "INFY" + tag);
        hold(userId, broker1, infy);
        hold(userId, broker2, infy);

        JsonNode byName = search(api, "infosys " + tag).get("holdings");
        assertEquals(1, byName.size(), "same instrument at two brokers is one hit");
        assertEquals(infy.toString(), byName.get(0).get("instrumentId").asText());
        assertEquals("INFY" + tag, byName.get(0).get("symbol").asText());
        assertEquals(1, search(api, "infy" + tag).get("holdings").size());

        override(userId, infy, "My Infy " + tag, "MYINFY" + tag);
        assertEquals(1, search(api, "my infy " + tag).get("holdings").size());
        assertEquals(1, search(api, "myinfy" + tag).get("holdings").size());
        assertEquals(0, search(api, "infosys " + tag).get("holdings").size(), "override replaces the catalog name");
        JsonNode hit = search(api, "my infy " + tag).get("holdings").get(0);
        assertEquals("My Infy " + tag, hit.get("name").asText());
        assertEquals("MYINFY" + tag, hit.get("symbol").asText());
    }

    @Test
    void holdingNotHeldByTheUserIsNotReturned() throws Exception {
        UUID notHeld = instrument("Wipro " + tag, "WIPRO" + tag);
        assertEquals(0, search(api, "wipro " + tag).get("holdings").size());
        UUID otherBroker = broker(other, "Other broker " + tag);
        hold(otherUserId, otherBroker, notHeld);
        assertEquals(0, search(api, "wipro " + tag).get("holdings").size());
    }

    @Test
    void peopleLoansReportsAndDashboardsMatch() throws Exception {
        UUID person = counterparty(userId, "Rahul " + tag);
        UUID loan = loan(api, "Home loan " + tag, "SBI" + tag, "ACC" + tag + "99");
        UUID report = report(userId, "PhonePe 10% " + tag);
        UUID board = dashboard(userId, "Home " + tag, true);

        JsonNode people = search(api, "rahul " + tag).get("people");
        assertEquals(List.of(person.toString()), texts(people, "id"));
        assertEquals("Rahul " + tag, people.get(0).get("name").asText());

        assertEquals(List.of(loan.toString()), texts(search(api, "home loan " + tag).get("loans"), "id"));
        JsonNode byLender = search(api, "sbi" + tag).get("loans");
        assertEquals(List.of(loan.toString()), texts(byLender, "id"));
        assertEquals("SBI" + tag, byLender.get(0).get("lender").asText());
        assertEquals(List.of(loan.toString()), texts(search(api, "acc" + tag + "99").get("loans"), "id"));

        JsonNode reports = search(api, "phonepe 10% " + tag).get("reports");
        assertEquals(List.of(report.toString()), texts(reports, "id"));
        assertEquals("KPI", reports.get(0).get("type").asText());

        JsonNode boards = search(api, "home " + tag).get("dashboards");
        assertEquals(List.of(board.toString()), texts(boards, "id"));
        assertTrue(boards.get(0).get("isDefault").asBoolean());
    }

    // ---------- caps, ordering, escaping ----------

    @Test
    void eachGroupIsCappedAtFive() throws Exception {
        for (int i = 0; i < 7; i++) {
            account(api, "Cap acct " + tag + i, null);
            txn(api, bank, "Cap txn " + tag + i, -1 - i, i);
            counterparty(userId, "Cap person " + tag + i);
            loan(api, "Cap loan " + tag + i, "L", null);
            report(userId, "Cap report " + tag + i);
            dashboard(userId, "Cap board " + tag + i, false);
            UUID inst = instrument("Cap stock " + tag + i, "CAP" + i);
            hold(userId, bank, inst);
        }
        JsonNode r = search(api, "cap");
        for (String group : List.of("accounts", "transactions", "holdings", "people", "loans", "reports", "dashboards")) {
            assertEquals(5, r.get(group).size(), group);
        }
    }

    @Test
    void transactionsAreNewestFirstAndSmallGroupsPrefixFirstThenAlphabetical() throws Exception {
        UUID oldest = txn(api, bank, "Order " + tag + " a", -1, 9);
        UUID newest = txn(api, bank, "Order " + tag + " b", -1, 1);
        UUID middle = txn(api, bank, "Order " + tag + " c", -1, 5);
        assertEquals(List.of(newest.toString(), middle.toString(), oldest.toString()),
                texts(search(api, "order " + tag).get("transactions"), "id"));

        counterparty(userId, "Zed sorttag" + tag);
        counterparty(userId, "Bob sorttag" + tag);
        counterparty(userId, "sorttag" + tag + " Yan");
        counterparty(userId, "sorttag" + tag + " Abe");
        assertEquals(List.of("sorttag" + tag + " Abe", "sorttag" + tag + " Yan", "Bob sorttag" + tag, "Zed sorttag" + tag),
                texts(search(api, "sorttag" + tag).get("people"), "name"));
    }

    @Test
    void percentAndUnderscoreMatchLiterally() throws Exception {
        report(userId, "Save 10% rule " + tag);
        report(userId, "Save 10x rule " + tag);
        report(userId, "snake_case " + tag);
        report(userId, "snakeXcase " + tag);

        assertEquals(List.of("Save 10% rule " + tag), texts(search(api, "10% rule").get("reports"), "name"));
        assertEquals(List.of("snake_case " + tag), texts(search(api, "snake_case").get("reports"), "name"));
        assertEquals(0, search(api, "%%%").get("reports").size());
        assertEquals(0, search(api, "___").get("reports").size());
    }

    // ---------- validation ----------

    @Test
    void shortOrLongQueryIs400AndPaddedQueryIsTrimmed() throws Exception {
        api.getJson("/api/v1/search?q=ab", 400);
        api.getJson("/api/v1/search?q=" + "a".repeat(101), 400);
        api.getJson("/api/v1/search?q=  ab  ", 400);
        api.getJson("/api/v1/search", 400);
        search(api, "a".repeat(100));

        report(userId, "Padded " + tag);
        assertEquals(1, search(api, "  padded " + tag + "  ").get("reports").size());
    }

    @Test
    void emptyGroupsAreEmptyArraysNotNull() throws Exception {
        JsonNode r = search(api, "nomatch" + tag + "zzz");
        for (String group : List.of("accounts", "transactions", "holdings", "people", "loans", "reports", "dashboards")) {
            assertTrue(r.get(group).isArray(), group);
            assertEquals(0, r.get(group).size(), group);
        }
    }

    // ---------- tenancy ----------

    @Test
    void anotherUsersMatchingRowsNeverAppearInAnyGroup() throws Exception {
        UUID otherBank = account(other, "Shared word acct " + tag, "hdfc");
        creditCard(other, "Shared word card " + tag, "9876");
        txn(other, otherBank, "Shared word txn " + tag, -777.25, 1);
        counterparty(otherUserId, "Shared word person " + tag);
        loan(other, "Shared word loan " + tag, "Shared word lender", "SHAREDWORD" + tag);
        report(otherUserId, "Shared word report " + tag);
        dashboard(otherUserId, "Shared word board " + tag, true);
        UUID inst = instrument("Shared word stock " + tag, "SHARED" + tag);
        hold(otherUserId, broker(other, "Other broker " + tag), inst);
        override(otherUserId, inst, "Shared word override " + tag, "SWO" + tag);

        for (String q : List.of("shared word", "shared" + tag, "swo" + tag, "9876", "777.25", "hdfc")) {
            JsonNode r = search(api, q);
            for (String group : List.of("accounts", "transactions", "holdings", "people", "loans", "reports", "dashboards")) {
                assertEquals(0, r.get(group).size(), q + " / " + group);
            }
        }
        assertEquals(1, search(other, "shared word acct").get("accounts").size());
    }
}
