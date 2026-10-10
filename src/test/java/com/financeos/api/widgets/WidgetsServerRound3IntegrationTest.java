package com.financeos.api.widgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Against real persistence (H2), round 3: a built-in's resolved definition saves as the caller's
 * own report and runs to the same data; a card's effective credit limit on every account
 * response; net worth breakdowns of accounts net worth leaves out.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WidgetsServerRound3IntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementRepository statementRepository;

    private ApiTestClient alice;
    private ApiTestClient bob;
    private UUID aliceId;
    private UUID bobId;
    private LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        today = AppTime.today();
        String a = "r3-a-" + UUID.randomUUID() + "@example.test";
        alice = ApiTestClient.signUp(mockMvc, mapper, a);
        aliceId = userRepository.findByEmail(a).orElseThrow().getId();
        String b = "r3-b-" + UUID.randomUUID() + "@example.test";
        bob = ApiTestClient.signUp(mockMvc, mapper, b);
        bobId = userRepository.findByEmail(b).orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(aliceId, bobId));
    }

    private void transaction(UUID accountId, LocalDate date, String amount, TransactionType type) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction(account, date, new BigDecimal(amount), "R3 " + type, TransactionSource.manual,
                type, false, false);
        t.setUser(account.getUser());
        transactionRepository.save(t);
    }

    private UUID bank(String name, boolean excluded) throws Exception {
        return alice.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", "1111",
                "openingBalance", 5000, "financialPosition", "asset", "excludeFromNetAsset", excluded));
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertTrue(actual != null && actual.isNumber(), "expected " + expected + " but was " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    // ------------------------------------------------------------------ resolved built-in definition

    @Test
    void aResolvedDefinitionSavesAsAReportThatRunsToTheBuiltinsData() throws Exception {
        UUID bank = bank("R3 Bank", false);
        transaction(bank, today.minusDays(3), "1200", TransactionType.DEBIT);
        transaction(bank, today.minusMonths(5), "800", TransactionType.DEBIT);

        Map<String, Object> body = Map.of("params", Map.of("months", 2));
        JsonNode resolved = alice.postJson("/api/v1/dashboards/builtins/spend_heatmap/definition", body, 200);
        assertEquals("spend_heatmap", resolved.get("key").asText());
        assertEquals("CHART", resolved.get("type").asText());
        assertEquals("transactions", resolved.get("datasource").asText());
        assertTrue(resolved.get("windowAsOf").isNull());

        UUID report = alice.create("/api/v1/reports", Map.of("name", resolved.get("label").asText(),
                "type", resolved.get("type").asText(), "datasource", resolved.get("datasource").asText(),
                "definition", resolved.get("definition")));
        JsonNode saved = alice.postJson("/api/v1/reports/" + report + "/data", null, 200);
        JsonNode builtin = alice.postJson("/api/v1/dashboards/builtins/spend_heatmap/data", body, 200);
        assertEquals(builtin, saved, "the saved duplicate runs to exactly the built-in's data");
        assertEquals(1, builtin.get("categories").size(),
                "only the spend inside the 2-month window: " + builtin);
        assertDecimal("1200", builtin.get("series").get(0).get("data").get(0));
    }

    @Test
    void aRewardWindowDefinitionIsPinnedToTodayAndStillSaves() throws Exception {
        JsonNode resolved = alice.postJson("/api/v1/dashboards/builtins/cap_headroom/definition", null, 200);

        assertEquals(today.toString(), resolved.get("windowAsOf").asText());
        alice.create("/api/v1/reports", Map.of("name", "My caps", "type", resolved.get("type").asText(),
                "datasource", resolved.get("datasource").asText(), "definition", resolved.get("definition")));
    }

    @Test
    void definitionErrorsAre400And404() throws Exception {
        assertEquals(400, alice.post("/api/v1/dashboards/builtins/card_utilisation/definition", null).getStatus());
        assertEquals(400, alice.post("/api/v1/dashboards/builtins/spend_heatmap/definition",
                Map.of("params", Map.of("months", 0))).getStatus());
        assertEquals(404, alice.post("/api/v1/dashboards/builtins/nope/definition", null).getStatus());
    }

    // ------------------------------------------------------------------ effective credit limit

    @Test
    void everyAccountResponseCarriesTheCardsEffectiveCreditLimit() throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("type", "credit_card", "name", "R3 Card", "last4", "4321",
                "creditLimit", 100000, "anniversaryDate", today.minusMonths(3).toString(),
                "financialPosition", "liability", "excludeFromNetAsset", false));
        JsonNode created = alice.postJson("/api/v1/accounts", body, 201);
        assertDecimal("100000", created.get("effectiveCreditLimit"));
        UUID id = UUID.fromString(created.get("id").asText());

        body.put("creditLimit", 75000);
        assertDecimal("75000", alice.putJson("/api/v1/accounts/" + id, body, 200).get("effectiveCreditLimit"));
        assertDecimal("75000", alice.getJson("/api/v1/accounts/" + id, 200).get("effectiveCreditLimit"));
        assertDecimal("75000", listed(id).get("effectiveCreditLimit"));

        // A card with no usable own limit falls back to its latest statement's, on get and the list's SQL.
        jdbc.update("UPDATE account_credit_card_details SET credit_limit = 0 WHERE account_id = ?", id.toString());
        assertTrue(alice.getJson("/api/v1/accounts/" + id, 200).get("effectiveCreditLimit").isNull(),
                "no limit anywhere yet");
        assertTrue(listed(id).get("effectiveCreditLimit").isNull());
        statement(id, today.minusDays(10), "2000", "60000");
        assertDecimal("60000", alice.getJson("/api/v1/accounts/" + id, 200).get("effectiveCreditLimit"));
        assertDecimal("60000", listed(id).get("effectiveCreditLimit"));
    }

    private JsonNode listed(UUID id) throws Exception {
        for (JsonNode a : alice.getJson("/api/v1/accounts", 200)) {
            if (a.get("id").asText().equals(id.toString())) {
                return a;
            }
        }
        throw new AssertionError("not listed: " + id);
    }

    private void statement(UUID accountId, LocalDate periodEnd, String closing, String limit) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Statement s = new Statement();
        s.setUser(account.getUser());
        s.setAccount(account);
        s.setSource(StatementSource.file_upload);
        s.setStatementType("credit_card");
        s.setPeriodStart(periodEnd.minusMonths(1).plusDays(1));
        s.setPeriodEnd(periodEnd);
        s.setClosingBalance(new BigDecimal(closing));
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(account.getUser());
        d.setTotalAmountDue(new BigDecimal(closing));
        d.setPaymentDueDate(periodEnd.plusDays(18));
        d.setCreditLimit(new BigDecimal(limit));
        s.setCreditCardDetails(d);
        statementRepository.save(s);
    }

    // ------------------------------------------------------------------ net worth breakdown of left-out accounts

    private String breakdownPath(UUID id) {
        return "/api/v1/report/datasource/net_worth/rows/" + id + "/breakdown";
    }

    @Test
    void anExcludedAccountsBreakdownIsServedAndFlagged() throws Exception {
        UUID hidden = bank("R3 Hidden", true);
        transaction(hidden, today.minusDays(1), "700", TransactionType.DEBIT);

        JsonNode r = alice.getJson(breakdownPath(hidden), 200);

        assertTrue(r.get("notCounted").asBoolean());
        assertEquals("Excluded from net worth", r.get("notCountedReason").asText());
        assertEquals("Not counted in net worth", r.get("subtitle").asText());
        assertDecimal("4300", r.get("total"));
        assertEquals(1, alice.getJson(breakdownPath(hidden) + "/sections/transactions", 200).get("rows").size());
        // Another user's account is still 404, on both the breakdown and its sections.
        assertEquals(404, bob.get(breakdownPath(hidden)).getStatus());
        assertEquals(404, bob.get(breakdownPath(hidden) + "/sections/transactions").getStatus());
    }

    @Test
    void aClosedAccountsBreakdownIsServedAndFlagged() throws Exception {
        UUID closed = bank("R3 Closed", false);
        alice.postJson("/api/v1/accounts/" + closed + "/close", Map.of("closedOn", today.toString()), 200);

        JsonNode r = alice.getJson(breakdownPath(closed), 200);

        assertTrue(r.get("notCounted").asBoolean());
        assertEquals("Closed", r.get("notCountedReason").asText());
        assertDecimal("5000", r.get("total"));
        assertEquals(404, bob.get(breakdownPath(closed)).getStatus());
    }

    @Test
    void aCountedAccountsBreakdownIsNotFlagged() throws Exception {
        UUID open = bank("R3 Open", false);

        JsonNode r = alice.getJson(breakdownPath(open), 200);

        assertFalse(r.get("notCounted").asBoolean());
        assertTrue(r.get("notCountedReason").isNull());
        assertEquals("Asset", r.get("subtitle").asText());
    }
}
