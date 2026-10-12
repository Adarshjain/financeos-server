package com.financeos.domain.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The anchored balance counts every transaction dated after the statement's last billed day
 * ({@link BalanceMath#cutoff}), not only those after its period end: an issuer that cuts a
 * statement by posting date lists nothing dated on the period end itself, so purchases dated that
 * day are unbilled. The single-account path, the list (batch SQL), the net-worth breakdown and
 * the bill's unbilled spend all apply the same cutoff.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnchorCutoffIntegrationTest {

    private static final String NET_WORTH = "/api/v1/report/datasource/net_worth/rows/";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementPersistenceService statementPersistenceService;

    private ApiTestClient api;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        String email = "anchor-cutoff-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        userId = user.getId();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    // ------------------------------------------------------------------ the posting-date statement

    @Test
    void purchasesDatedOnThePeriodEndCountWhenTheStatementListsNothingThatDay() throws Exception {
        LocalDate periodEnd = LocalDate.of(2026, 9, 18);
        UUID card = card("Premier");
        UUID statement = statement(card, LocalDate.of(2026, 8, 19), periodEnd, "56843.16");
        link(statement,
                txn(card, periodEnd.minusDays(2), "324", TransactionType.DEBIT, TransactionSource.file_upload),
                txn(card, periodEnd.minusDays(1), "5096.17", TransactionType.DEBIT, TransactionSource.file_upload));
        txn(card, periodEnd.minusDays(1), "100", TransactionType.DEBIT, TransactionSource.manual); // on the last billed day: billed
        UUID onPeriodEnd = txn(card, periodEnd, "10192.34", TransactionType.DEBIT, TransactionSource.manual);
        UUID after = txn(card, periodEnd.plusDays(1), "50", TransactionType.DEBIT, TransactionSource.manual);
        UUID payment = txn(card, periodEnd.plusDays(2), "200", TransactionType.CREDIT, TransactionSource.manual);

        JsonNode single = api.getJson("/api/v1/accounts/" + card, 200);
        assertDecimal("-66885.50", single.get("balance"));
        assertTrue(single.get("balanceAnchored").asBoolean());
        assertEquals("2026-09-18", single.get("anchorDate").asText(), "the anchor stays the statement's period end");
        assertDecimal("-66885.50", listed(card).get("balance"));

        JsonNode body = api.getJson(NET_WORTH + card + "/breakdown", 200);
        assertReconciles(body);
        assertEquals(List.of(
                        step("start", "Owed on statement ending 18/09/2026", "56843.16"),
                        step("add", "Spends since 17/09/2026 (2)", "10242.34"),
                        step("subtract", "Payments and refunds since 17/09/2026 (1)", "200"),
                        step("equals", "Outstanding", "66885.5")),
                steps(body));
        JsonNode section = body.get("sections").get(0);
        assertEquals("Transactions after 17/09/2026", section.get("label").asText());
        assertEquals(List.of(payment, after, onPeriodEnd), rowIds(section.get("table")));

        JsonNode bills = api.getJson("/api/v1/bills?accountId=" + card, 200);
        assertEquals(1, bills.size());
        assertDecimal("10242.34", bills.get(0).get("unbilledAmount"));
    }

    @Test
    void aBankStatementWithTheSameShapeCountsTheCreditDatedOnItsPeriodEnd() throws Exception {
        LocalDate periodEnd = LocalDate.of(2026, 9, 30);
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Cutoff Bank", "last4", "1234",
                "openingBalance", 100, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID statement = statement(bank, LocalDate.of(2026, 9, 1), periodEnd, "500");
        link(statement, txn(bank, periodEnd.minusDays(1), "400", TransactionType.CREDIT, TransactionSource.file_upload));
        UUID salary = txn(bank, periodEnd, "50", TransactionType.CREDIT, TransactionSource.manual);

        JsonNode single = api.getJson("/api/v1/accounts/" + bank, 200);
        assertDecimal("550", single.get("balance"));
        assertDecimal("550", listed(bank).get("balance"));

        JsonNode body = api.getJson(NET_WORTH + bank + "/breakdown", 200);
        assertReconciles(body);
        assertEquals(List.of(
                        step("start", "Closing balance on statement ending 30/09/2026", "500"),
                        step("add", "Credits after 29/09/2026 (1)", "50"),
                        step("equals", "Balance", "550")),
                steps(body));
        assertEquals(List.of(salary), rowIds(body.get("sections").get(0).get("table")));
    }

    // ------------------------------------------------------------------ shapes that keep the period end

    @Test
    void aStatementListingItsPeriodEndBillsThroughThePeriodEnd() throws Exception {
        LocalDate periodEnd = LocalDate.of(2026, 9, 30);
        UUID card = card("Through close");
        UUID statement = statement(card, LocalDate.of(2026, 9, 1), periodEnd, "1000");
        link(statement,
                txn(card, periodEnd.minusDays(1), "600", TransactionType.DEBIT, TransactionSource.file_upload),
                txn(card, periodEnd, "400", TransactionType.DEBIT, TransactionSource.file_upload));
        txn(card, periodEnd, "40", TransactionType.DEBIT, TransactionSource.manual); // on the period end: billed
        UUID next = txn(card, periodEnd.plusDays(1), "60", TransactionType.DEBIT, TransactionSource.manual);

        assertDecimal("-1060", api.getJson("/api/v1/accounts/" + card, 200).get("balance"));
        assertDecimal("-1060", listed(card).get("balance"));
        JsonNode body = api.getJson(NET_WORTH + card + "/breakdown", 200);
        assertReconciles(body);
        assertEquals(step("add", "Spends since 30/09/2026 (1)", "60"), steps(body).get(1));
        assertEquals("Transactions after 30/09/2026", body.get("sections").get(0).get("label").asText());
        assertEquals(List.of(next), rowIds(body.get("sections").get(0).get("table")));
    }

    @Test
    void aStatementWithoutLinesBillsThroughItsPeriodEnd() throws Exception {
        LocalDate periodEnd = LocalDate.of(2026, 9, 30);
        UUID card = card("No lines");
        statement(card, LocalDate.of(2026, 9, 1), periodEnd, "1000");
        txn(card, periodEnd, "40", TransactionType.DEBIT, TransactionSource.manual);
        txn(card, periodEnd.plusDays(1), "60", TransactionType.DEBIT, TransactionSource.manual);

        assertDecimal("-1060", api.getJson("/api/v1/accounts/" + card, 200).get("balance"));
        assertDecimal("-1060", listed(card).get("balance"));
        assertEquals("Transactions after 30/09/2026",
                api.getJson(NET_WORTH + card + "/breakdown", 200).get("sections").get(0).get("label").asText());
    }

    @Test
    void aLineDatedAfterThePeriodEndNeverMovesTheCutoffLater() throws Exception {
        LocalDate periodEnd = LocalDate.of(2026, 9, 30);
        UUID card = card("Late line");
        UUID statement = statement(card, LocalDate.of(2026, 9, 1), periodEnd, "1000");
        link(statement,
                txn(card, periodEnd.minusDays(1), "970", TransactionType.DEBIT, TransactionSource.file_upload),
                txn(card, periodEnd.plusDays(2), "30", TransactionType.DEBIT, TransactionSource.file_upload));
        UUID onPeriodEnd = txn(card, periodEnd, "40", TransactionType.DEBIT, TransactionSource.manual);
        UUID next = txn(card, periodEnd.plusDays(1), "60", TransactionType.DEBIT, TransactionSource.manual);

        JsonNode body = api.getJson(NET_WORTH + card + "/breakdown", 200);
        assertEquals("Transactions after 30/09/2026", body.get("sections").get(0).get("label").asText());
        List<UUID> listed = rowIds(body.get("sections").get(0).get("table"));
        assertTrue(listed.contains(next), "dated after the period end: counted");
        assertFalse(listed.contains(onPeriodEnd), "dated on the period end the statement lists: billed");
        assertDecimal(api.getJson("/api/v1/accounts/" + card, 200).get("balance").decimalValue(), listed(card).get("balance"));
    }

    // ------------------------------------------------------------------ helpers

    private UUID card(String name) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", name, "last4", "4321",
                "creditLimit", 200000, "anniversaryDate", TODAY.toString(), "financialPosition", "liability",
                "excludeFromNetAsset", false));
    }

    /** One AUTO_INGEST statement for the account; a card statement also gets its card details (so it is a bill). */
    private UUID statement(UUID accountId, LocalDate periodStart, LocalDate periodEnd, String closing) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        boolean card = account.getType() == AccountType.credit_card;
        BigDecimal closingBalance = new BigDecimal(closing);
        Map<String, Object> cardFields = card
                ? Map.of("total_amount_due", closingBalance, "minimum_amount_due", new BigDecimal("500"),
                        "payment_due_date", periodEnd.plusDays(15), "previous_balance", BigDecimal.ZERO)
                : Map.of();
        StatementDraft draft = new StatementDraft(card ? "credit_card" : "bank_account", periodStart, periodEnd,
                BigDecimal.ZERO, closingBalance, "HSBC", "XXXX4321", 2, 0, closingBalance, BigDecimal.ZERO,
                "heuristic", new BigDecimal("100.0"), true, StatementVerdict.AUTO_INGEST, cardFields);
        return statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                periodEnd + ".pdf", "sha-" + UUID.randomUUID(), draft).orElseThrow().getId();
    }

    private void link(UUID statementId, UUID... transactionIds) {
        List<StatementPersistenceService.TxnLink> links = new ArrayList<>();
        for (int i = 0; i < transactionIds.length; i++) {
            links.add(new StatementPersistenceService.TxnLink(transactionIds[i], i, null, null));
        }
        statementPersistenceService.linkTransactions(statementId, links);
    }

    private UUID txn(UUID accountId, LocalDate date, String amount, TransactionType type, TransactionSource source) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction(account, date, new BigDecimal(amount), "t " + date, source, type, false, false);
        t.setUser(user);
        return transactionRepository.save(t).getId();
    }

    /** The account's row of GET /accounts (the batch balance path). */
    private JsonNode listed(UUID accountId) throws Exception {
        JsonNode list = api.getJson("/api/v1/accounts", 200);
        JsonNode rows = list.isArray() ? list : list.get("content");
        for (JsonNode row : rows) {
            if (accountId.toString().equals(row.get("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("account " + accountId + " not listed");
    }

    private static void assertDecimal(String expected, JsonNode actual) {
        assertDecimal(new BigDecimal(expected), actual);
    }

    private static void assertDecimal(BigDecimal expected, JsonNode actual) {
        assertNotNull(actual);
        assertEquals(0, expected.compareTo(actual.decimalValue()), expected + " vs " + actual);
    }

    private static List<String> step(String op, String label, String amount) {
        return List.of(op, label, amount);
    }

    private static List<List<String>> steps(JsonNode body) {
        List<List<String>> steps = new ArrayList<>();
        for (JsonNode s : body.get("steps")) {
            JsonNode amount = s.get("amount");
            steps.add(List.of(s.get("op").asText(), s.get("label").asText(),
                    amount == null || amount.isNull() ? "" : amount.decimalValue().stripTrailingZeros().toPlainString()));
        }
        return steps;
    }

    private static List<UUID> rowIds(JsonNode table) {
        List<UUID> ids = new ArrayList<>();
        table.get("rows").forEach(r -> ids.add(UUID.fromString(r.get("id").asText())));
        return ids;
    }

    private static void assertReconciles(JsonNode body) {
        BigDecimal running = BigDecimal.ZERO;
        BigDecimal equals = null;
        for (JsonNode step : body.get("steps")) {
            switch (step.get("op").asText()) {
                case "start", "add" -> running = running.add(step.get("amount").decimalValue());
                case "subtract" -> running = running.subtract(step.get("amount").decimalValue());
                case "equals" -> equals = step.get("amount").decimalValue();
                default -> { }
            }
        }
        assertNotNull(equals, "chain has an equals step");
        assertEquals(0, running.compareTo(equals), "chain " + running + " vs equals " + equals);
        assertEquals(0, equals.compareTo(body.get("total").decimalValue()), "equals is the total");
    }
}
