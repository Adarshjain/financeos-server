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
 * statement that has only a total amount due; a credit dated on the statement day settles the bill
 * the same day the balance stops owing it, linked or not; excluded rows count on both sides; and a
 * credit beyond the bill comes off the unbilled spend.
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
    void aCreditDatedOnTheStatementDaySettlesTheBillTheDayTheBalanceStopsOwingItLinkedOrNot() throws Exception {
        Statement s = statement(END, new BigDecimal("6000"), new BigDecimal("6000"), StatementVerdict.AUTO_INGEST);
        // The issuer cuts by posting date: the statement's last line is dated the day before the period end.
        UUID billed = save(END.minusDays(1), "6000", TransactionType.DEBIT, false);
        statementPersistenceService.linkTransactions(s.getId(), List.of(
                new StatementPersistenceService.TxnLink(billed, 0, null, null)));
        save(END, "999", TransactionType.DEBIT, false);  // bought on the close, not on the statement
        save(END, "6000", TransactionType.CREDIT, false); // paid on the statement day, not on it either, never linked
        save(END.plusDays(1), "250", TransactionType.DEBIT, true); // excluded: the balance counts it, so does the bill

        JsonNode bill = find(api.getJson("/api/v1/bills", 200), "accountId", card.getId());
        assertEquals("PAID", bill.get("status").asText(), "the credit on the period end settles the bill, no link needed");
        assertEquals("CREDITS", bill.get("paidSource").asText());
        assertEquals(0, money(bill.get("remainingAmount")).signum());
        assertEquals(bd("1249"), money(bill.get("unbilledAmount")), "the close-day purchase plus the excluded one");

        JsonNode single = api.getJson("/api/v1/accounts/" + card.getId(), 200);
        assertEquals(bd("-1249"), money(single.get("balance")), "closing 6000 − payment 6000 + purchases 999 + 250");
        assertEquals(bd("1249"), money(bill.get("remainingAmount")).add(money(bill.get("unbilledAmount"))),
                "to pay + unbilled is what the accounts page shows as outstanding");
    }

    @Test
    void creditsBeyondTheBillComeOffTheUnbilledSpend() throws Exception {
        statement(END, new BigDecimal("6000"), new BigDecimal("6000"), StatementVerdict.AUTO_INGEST);
        save(END.plusDays(1), "999", TransactionType.DEBIT, false);
        save(END.plusDays(2), "6500", TransactionType.CREDIT, false); // overpaid by 500

        JsonNode bill = find(api.getJson("/api/v1/bills", 200), "accountId", card.getId());
        assertEquals("PAID", bill.get("status").asText());
        assertEquals(bd("499"), money(bill.get("unbilledAmount")), "999 less the 500 paid beyond the bill");
        JsonNode single = api.getJson("/api/v1/accounts/" + card.getId(), 200);
        assertEquals(bd("-499"), money(single.get("balance")));
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
        return save(date, amount, type, false);
    }

    private UUID save(LocalDate date, String amount, TransactionType type, boolean excluded) {
        return saveOn(card, date, amount, type, excluded);
    }

    private UUID saveOn(Account account, LocalDate date, String amount, TransactionType type, boolean excluded) {
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
        t.setTransactionExcluded(excluded);
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
