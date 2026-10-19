package com.financeos.api.statement;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statement detail's per-line origin (created vs matched, the same rule a delete uses) and its
 * closing balance against the ledger, over real persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementDetailLedgerIntegrationTest {

    private static final LocalDate PERIOD_END = LocalDate.of(2026, 2, 28);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementPersistenceService statementPersistenceService;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private User user;
    private UUID otherUserId;
    private Account bank;

    @BeforeEach
    void setUp() throws Exception {
        String email = "stmt-ledger-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        String otherEmail = "stmt-ledger-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();
        bank = account(Map.of("type", "bank_account", "name", "Ledger Savings", "last4", "7483",
                "openingBalance", 1000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId(), otherUserId));
    }

    @Test
    void linesAreCreatedOrMatchedAndAgreeWithTheDeletionPreview() throws Exception {
        Statement statement = statement(bank, StatementSource.file_upload, "feb.pdf", "bank_account", "1350.00");
        UUID created1 = txn(bank, TransactionSource.file_upload, "2026-02-10", "500.00", TransactionType.CREDIT, null);
        UUID created2 = txn(bank, TransactionSource.file_upload, "2026-02-12", "25.00", TransactionType.CREDIT, null);
        UUID matchedManual = txn(bank, TransactionSource.manual, "2026-02-20", "200.00", TransactionType.DEBIT, null);
        UUID sharedUpload = txn(bank, TransactionSource.file_upload, "2026-02-15", "30.00", TransactionType.CREDIT, null);
        link(statement, created1, created2, matchedManual, sharedUpload);
        // A second statement also has a line for the shared upload row, so deleting the first keeps it.
        Statement other = statementWithPeriod(bank, StatementSource.file_upload, "other.pdf", "bank_account", "0",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        link(other, sharedUpload);

        JsonNode detail = api.getJson("/api/v1/statements/" + statement.getId(), 200);
        assertEquals("created", origin(detail, created1));
        assertEquals("created", origin(detail, created2));
        assertEquals("matched", origin(detail, matchedManual));
        assertEquals("matched", origin(detail, sharedUpload));

        JsonNode preview = api.getJson("/api/v1/accounts/" + bank.getId() + "/statements/" + statement.getId()
                + "/deletion-preview", 200);
        assertEquals(count(detail, "created"), preview.get("transactionsToDelete").asInt());
        assertEquals(count(detail, "matched"), preview.get("transactionsKept").asInt());
        assertEquals(2, count(detail, "created"));
    }

    @Test
    void gmailStatementCreatesOnlyTheRowsKeyedByItsMessageId() throws Exception {
        Statement statement = statement(bank, StatementSource.gmail, "msg1", "bank_account", "0");
        UUID created = txn(bank, TransactionSource.gmail_statement, "2026-02-10", "10.00", TransactionType.CREDIT, "msg1:0");
        UUID otherMessage = txn(bank, TransactionSource.gmail_statement, "2026-02-11", "10.00", TransactionType.CREDIT, "msg2:0");
        UUID manual = txn(bank, TransactionSource.manual, "2026-02-12", "10.00", TransactionType.CREDIT, null);
        link(statement, created, otherMessage, manual);

        JsonNode detail = api.getJson("/api/v1/statements/" + statement.getId(), 200);
        assertEquals("created", origin(detail, created));
        assertEquals("matched", origin(detail, otherMessage));
        assertEquals("matched", origin(detail, manual));
        JsonNode preview = api.getJson("/api/v1/accounts/" + bank.getId() + "/statements/" + statement.getId()
                + "/deletion-preview", 200);
        assertEquals(1, preview.get("transactionsToDelete").asInt());
        assertEquals(2, preview.get("transactionsKept").asInt());
    }

    @Test
    void ledgerMatchesExactlyAndIncludesPeriodEndDayButNotLaterDays() throws Exception {
        // 1000 opening + 500 - 200 + 50 on the period end day = 1350; the March debit is after it.
        Statement statement = statement(bank, StatementSource.file_upload, "feb.pdf", "bank_account", "1350.00");
        txn(bank, TransactionSource.manual, "2026-02-10", "500.00", TransactionType.CREDIT, null);
        txn(bank, TransactionSource.manual, "2026-02-20", "200.00", TransactionType.DEBIT, null);
        txn(bank, TransactionSource.manual, "2026-02-28", "50.00", TransactionType.CREDIT, null);
        txn(bank, TransactionSource.manual, "2026-03-05", "1000.00", TransactionType.DEBIT, null);

        JsonNode detail = api.getJson("/api/v1/statements/" + statement.getId(), 200);
        assertEquals(0, new BigDecimal("1350").compareTo(detail.get("ledgerBalanceAtClose").decimalValue()));
        assertTrue(detail.get("matchesLedger").asBoolean());
    }

    @Test
    void ledgerOffByAnAmountDoesNotMatchAndReportsTheLedgerValue() throws Exception {
        Statement statement = statement(bank, StatementSource.file_upload, "feb.pdf", "bank_account", "1400.00");
        txn(bank, TransactionSource.manual, "2026-02-10", "500.00", TransactionType.CREDIT, null);
        txn(bank, TransactionSource.manual, "2026-02-20", "200.00", TransactionType.DEBIT, null);

        JsonNode detail = api.getJson("/api/v1/statements/" + statement.getId(), 200);
        assertEquals(0, new BigDecimal("1300").compareTo(detail.get("ledgerBalanceAtClose").decimalValue()));
        assertFalse(detail.get("matchesLedger").asBoolean());
    }

    @Test
    void creditCardLedgerIsTheAmountOwed() throws Exception {
        Account card = account(Map.of("type", "credit_card", "name", "Ledger Card", "last4", "8421",
                "creditLimit", 200000, "anniversaryDate", "2026-01-15",
                "financialPosition", "liability", "excludeFromNetAsset", false));
        // Spent 700 and paid 200: the card owes 500, which is what the statement prints.
        Statement statement = statement(card, StatementSource.file_upload, "card.pdf", "credit_card", "500.00");
        txn(card, TransactionSource.manual, "2026-02-05", "700.00", TransactionType.DEBIT, null);
        txn(card, TransactionSource.manual, "2026-02-25", "200.00", TransactionType.CREDIT, null);
        txn(card, TransactionSource.manual, "2026-03-02", "90.00", TransactionType.DEBIT, null);

        JsonNode detail = api.getJson("/api/v1/statements/" + statement.getId(), 200);
        assertEquals(0, new BigDecimal("500").compareTo(detail.get("ledgerBalanceAtClose").decimalValue()));
        assertTrue(detail.get("matchesLedger").asBoolean());
    }

    @Test
    void noClosingBalanceGivesNullLedgerFields() throws Exception {
        Statement statement = statement(bank, StatementSource.file_upload, "feb.pdf", "bank_account", null);

        JsonNode detail = api.getJson("/api/v1/statements/" + statement.getId(), 200);
        assertTrue(detail.get("ledgerBalanceAtClose").isNull());
        assertTrue(detail.get("matchesLedger").isNull());
    }

    @Test
    void accountTypeWithoutComparableBalanceGivesNullLedgerFields() throws Exception {
        Account broker = account(Map.of("type", "broker", "name", "Ledger Broker", "provider", "Zerodha",
                "clientId", "CL1", "cashBalance", 5000, "financialPosition", "asset", "excludeFromNetAsset", false));
        Statement statement = statement(broker, StatementSource.file_upload, "broker.pdf", "bank_account", "100.00");

        JsonNode detail = api.getJson("/api/v1/statements/" + statement.getId(), 200);
        assertNotNull(detail.get("ledgerBalanceAtClose"));
        assertTrue(detail.get("ledgerBalanceAtClose").isNull());
        assertTrue(detail.get("matchesLedger").isNull());
    }

    @Test
    void anotherUsersStatementIsStillRejected() throws Exception {
        Statement statement = statement(bank, StatementSource.file_upload, "feb.pdf", "bank_account", "1000.00");

        int status = otherApi.get("/api/v1/statements/" + statement.getId()).getStatus();
        assertTrue(status == 400 || status == 404, "unexpected status " + status);
        assertEquals(404, otherApi.get("/api/v1/statements/" + UUID.randomUUID()).getStatus());
    }

    private Account account(Map<String, Object> body) throws Exception {
        return accountRepository.findById(api.create("/api/v1/accounts", body)).orElseThrow();
    }

    private Statement statement(Account account, StatementSource source, String ref, String type, String closing) {
        return statementWithPeriod(account, source, ref, type, closing, LocalDate.of(2026, 2, 1), PERIOD_END);
    }

    private Statement statementWithPeriod(Account account, StatementSource source, String ref, String type,
                                          String closing, LocalDate start, LocalDate end) {
        StatementDraft draft = new StatementDraft(type, start, end, BigDecimal.ZERO,
                closing == null ? null : new BigDecimal(closing), "HSBC", "xxxx7483", 3, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, "balance_chain", new BigDecimal("100.0"), true,
                StatementVerdict.AUTO_INGEST, Map.of());
        return statementPersistenceService.createIfNew(user, account, source, ref, "sha-" + ref + "-" + end, draft)
                .orElseThrow();
    }

    private UUID txn(Account account, TransactionSource source, String date, String amount, TransactionType type,
                     String sourceMessageId) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setDate(LocalDate.parse(date));
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(source);
        t.setSourceMessageId(sourceMessageId);
        t.setSourcedDescription("line " + date);
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        return transactionRepository.save(t).getId();
    }

    private void link(Statement statement, UUID... transactionIds) {
        List<StatementPersistenceService.TxnLink> links = new java.util.ArrayList<>();
        for (int i = 0; i < transactionIds.length; i++) {
            links.add(new StatementPersistenceService.TxnLink(transactionIds[i], i, null, null));
        }
        statementPersistenceService.linkTransactions(statement.getId(), links);
    }

    private static String origin(JsonNode detail, UUID transactionId) {
        for (JsonNode line : detail.get("lines")) {
            if (transactionId.toString().equals(line.get("transactionId").asText())) {
                return line.get("origin").asText();
            }
        }
        throw new AssertionError("no line for " + transactionId);
    }

    private static int count(JsonNode detail, String origin) {
        int n = 0;
        for (JsonNode line : detail.get("lines")) {
            if (origin.equals(line.get("origin").asText())) {
                n++;
            }
        }
        return n;
    }
}
