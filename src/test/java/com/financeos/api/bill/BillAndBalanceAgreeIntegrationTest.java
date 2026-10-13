package com.financeos.api.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.statement.StatementSource;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * The Bills due widget (GET /bills: to pay + unbilled) and the accounts page (GET /accounts: the
 * negative balance) owe the same money, over the real queries: the balance anchors on a card
 * statement that has only a total amount due, and a linked payment dated on the statement day
 * settles the bill the same day the balance stops owing it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BillAndBalanceAgreeIntegrationTest {

    private static final LocalDate PREV_END = LocalDate.of(2026, 8, 18);
    private static final LocalDate END = LocalDate.of(2026, 9, 18);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementPersistenceService statementPersistenceService;

    private ApiTestClient api;
    private User user;
    private UUID userId;
    private Account card;

    @BeforeEach
    void setUp() throws Exception {
        String email = "bill-balance-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        userId = user.getId();
        UUID cardId = api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", "Posting-lag Card",
                "last4", "8421", "creditLimit", 200000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        card = accountRepository.findById(cardId).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId));
    }

    @Test
    void balanceAnchorsOnACardStatementThatOnlyPrintsItsTotalDueSoBothSurfacesOweTheSame() throws Exception {
        statement(PREV_END, new BigDecimal("10000"), new BigDecimal("10000"), StatementVerdict.AUTO_INGEST);
        // The parser could not reconcile the latest statement: no closing balance, but the total due is printed.
        Statement latest = statement(END, null, new BigDecimal("25000"), StatementVerdict.NEEDS_REVIEW);
        save(END.minusDays(8), "500", TransactionType.DEBIT);   // inside the latest period: billed
        save(END.plusDays(2), "999", TransactionType.DEBIT);    // after it: unbilled

        JsonNode single = api.getJson("/api/v1/accounts/" + card.getId(), 200);
        assertEquals(bd("-25999"), money(single.get("balance")), "the latest statement's total due plus the unbilled purchase");
        assertTrue(single.get("balanceAnchored").asBoolean());
        assertEquals(END.toString(), single.get("anchorDate").asText());

        JsonNode listed = find(api.getJson("/api/v1/accounts", 200), "id", card.getId());
        assertEquals(bd("-25999"), money(listed.get("balance")), "the list (batch query) agrees");
        assertEquals(END.toString(), listed.get("anchorDate").asText());

        JsonNode bill = find(api.getJson("/api/v1/bills", 200), "accountId", card.getId());
        assertEquals(latest.getId().toString(), bill.get("statementId").asText());
        assertEquals(bd("25000"), money(bill.get("remainingAmount")));
        assertEquals(bd("999"), money(bill.get("unbilledAmount")));
        assertEquals(bd("25999"), money(bill.get("remainingAmount")).add(money(bill.get("unbilledAmount"))),
                "to pay + unbilled is what the accounts page shows as outstanding");
    }

    @Test
    void linkedPaymentDatedOnTheStatementDaySettlesTheBillTheDayTheBalanceStopsOwingIt() throws Exception {
        Statement s = statement(END, new BigDecimal("6000"), new BigDecimal("6000"), StatementVerdict.AUTO_INGEST);
        // The issuer cuts by posting date: the statement's last line is dated the day before the period end.
        UUID billed = save(END.minusDays(1), "6000", TransactionType.DEBIT);
        statementPersistenceService.linkTransactions(s.getId(), List.of(
                new StatementPersistenceService.TxnLink(billed, 0, null, null)));
        save(END, "999", TransactionType.DEBIT);                     // bought on the close, not on the statement
        UUID cardCredit = save(END, "6000", TransactionType.CREDIT); // paid on the statement day, not on it either
        UUID bank = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Salary", "last4", "7483",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        UUID bankDebit = saveOn(accountRepository.findById(bank).orElseThrow(), END, "6000", TransactionType.DEBIT);

        JsonNode open = find(api.getJson("/api/v1/bills", 200), "accountId", card.getId());
        assertEquals("OVERDUE", open.get("status").asText(), "an unlinked credit is only a possible payment");
        assertEquals(bd("6000"), money(open.get("remainingAmount")));
        assertEquals(1, open.get("possiblePayments").size());

        api.postJson("/api/v1/transaction-links", Map.of("type", "CC_PAYMENT", "members", List.of(
                Map.of("transactionId", bankDebit.toString(), "isAnchor", true),
                Map.of("transactionId", cardCredit.toString(), "isAnchor", false))), 201);

        JsonNode paid = find(api.getJson("/api/v1/bills", 200), "accountId", card.getId());
        assertEquals("PAID", paid.get("status").asText(), "linked, the payment on the period end settles the bill");
        assertEquals("LINK", paid.get("paidSource").asText());
        assertEquals(0, money(paid.get("remainingAmount")).signum());
        assertEquals(bd("999"), money(paid.get("unbilledAmount")));

        JsonNode single = api.getJson("/api/v1/accounts/" + card.getId(), 200);
        assertEquals(bd("-999"), money(single.get("balance")), "closing 6000 − payment 6000 + purchase 999");
        assertEquals(bd("999"), money(paid.get("remainingAmount")).add(money(paid.get("unbilledAmount"))),
                "to pay + unbilled is what the accounts page shows as outstanding");
    }

    private Statement statement(LocalDate periodEnd, BigDecimal closing, BigDecimal totalDue, StatementVerdict verdict) {
        Map<String, Object> cardFields = new HashMap<>();
        cardFields.put("total_amount_due", totalDue);
        cardFields.put("minimum_amount_due", totalDue.multiply(new BigDecimal("0.05")));
        cardFields.put("payment_due_date", periodEnd.plusDays(18));
        cardFields.put("credit_limit", new BigDecimal("200000"));
        StatementDraft draft = new StatementDraft("credit_card", periodEnd.minusDays(30), periodEnd, null, closing,
                "HDFC Bank", "XXXX8421", 1, 0, totalDue, BigDecimal.ZERO, "heuristic", BigDecimal.ZERO, null,
                verdict, cardFields);
        return statementPersistenceService.createIfNew(user, card, StatementSource.file_upload,
                "card-" + periodEnd + ".pdf", "sha-" + periodEnd, draft).orElseThrow();
    }

    private UUID save(LocalDate date, String amount, TransactionType type) {
        return saveOn(card, date, amount, type);
    }

    private UUID saveOn(Account account, LocalDate date, String amount, TransactionType type) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(TransactionSource.manual);
        t.setSourcedDescription(type == TransactionType.CREDIT ? "PAYMENT RECEIVED" : "PURCHASE");
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        return transactionRepository.save(t).getId();
    }

    private static JsonNode find(JsonNode array, String field, UUID id) {
        for (JsonNode node : array) {
            if (id.toString().equals(node.path(field).asText())) {
                return node;
            }
        }
        throw new AssertionError("no element with " + field + " = " + id);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(JsonNode node) {
        return node.decimalValue().setScale(2, RoundingMode.HALF_UP);
    }
}
