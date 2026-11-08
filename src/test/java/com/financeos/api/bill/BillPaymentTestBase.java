package com.financeos.api.bill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.time.AppTime;
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
import com.financeos.domain.transaction.autolink.AutoLinkService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.support.ApiTestClient;
import com.financeos.support.UserDataCleanup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared setup for the bill-payment integration tests (H2, real beans): a user with a bank account "Equitas" and a
 * credit card, a bill of the card (period end 25 days before today, due 5 days before today), and helpers to add bank
 * debits, run an auto-link pass and read the rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class BillPaymentTestBase {

    static final String CRED_TEXT = "P2M-CRED Club-payment on C-UTIB0000114- HEAD OFFICE";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired StatementPersistenceService statementPersistenceService;
    @Autowired AutoLinkService autoLinkService;

    ApiTestClient api;
    User user;
    UUID userId;
    UUID bankId;
    Account card;
    final List<UUID> extraUsers = new ArrayList<>();

    final LocalDate today = AppTime.today();
    final LocalDate periodEnd = today.minusDays(25);
    final LocalDate due = today.minusDays(5);

    @BeforeEach
    void baseSetUp() throws Exception {
        String email = "bill-pay-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        userId = user.getId();
        bankId = bankAccount(api, "Equitas");
        card = accountRepository.findById(cardAccount("Posting Card", "8421")).orElseThrow();
    }

    @AfterEach
    void baseTearDown() {
        List<UUID> all = new ArrayList<>(extraUsers);
        all.add(userId);
        UserDataCleanup.deleteUsers(jdbc, all);
    }

    UUID bankAccount(ApiTestClient client, String name) throws Exception {
        return client.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name, "last4", "1234",
                "openingBalance", 100000, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    UUID cardAccount(String name, String last4) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "credit_card", "name", name, "last4", last4,
                "creditLimit", 200000, "anniversaryDate", "2026-01-15", "financialPosition", "liability", "excludeFromNetAsset", false));
    }

    /** A live statement of {@code account} with the total due, period ending {@code end} and due on {@code dueDate}. */
    Statement statement(Account account, LocalDate end, LocalDate dueDate, String total) {
        BigDecimal totalDue = new BigDecimal(total);
        Map<String, Object> cardFields = new HashMap<>();
        cardFields.put("total_amount_due", totalDue);
        cardFields.put("minimum_amount_due", totalDue.multiply(new BigDecimal("0.05")));
        cardFields.put("payment_due_date", dueDate);
        cardFields.put("credit_limit", new BigDecimal("200000"));
        StatementDraft draft = new StatementDraft("credit_card", end.minusDays(30), end, null, totalDue,
                "HDFC Bank", "XXXX8421", 1, 0, totalDue, BigDecimal.ZERO, "heuristic", BigDecimal.ZERO, null,
                StatementVerdict.AUTO_INGEST, cardFields);
        return statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                "card-" + UUID.randomUUID() + ".pdf", "sha-" + UUID.randomUUID(), draft).orElseThrow();
    }

    Statement bill(String total) {
        return statement(card, periodEnd, due, total);
    }

    /** A manual transaction (statement-text description) on an account. */
    UUID save(Account account, LocalDate date, String amount, TransactionType type, String text) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(TransactionSource.manual);
        t.setSourcedDescription(text);
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        return transactionRepository.save(t).getId();
    }

    Account bank() {
        return accountRepository.findById(bankId).orElseThrow();
    }

    /** A CRED-looking bank debit on the Equitas account. */
    UUID bankDebit(LocalDate date, String amount) {
        return save(bank(), date, amount, TransactionType.DEBIT, CRED_TEXT);
    }

    AutoLinkService.RunResult pass() {
        return autoLinkService.run(userId);
    }

    JsonNode billJson(UUID statementId) throws Exception {
        return api.getJson("/api/v1/bills/" + statementId, 200);
    }

    JsonNode markPaid(UUID statementId, Object body) throws Exception {
        return api.postJson("/api/v1/bills/" + statementId + "/mark-paid", body, 200);
    }

    int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    int paymentRows(UUID statementId) {
        return count("SELECT COUNT(*) FROM bill_payments WHERE statement_id = ?", statementId.toString());
    }

    int txnCount(UUID accountId) {
        return count("SELECT COUNT(*) FROM transactions WHERE account_id = ? AND user_id = ?", accountId.toString(), userId.toString());
    }

    boolean exists(UUID transactionId) {
        return count("SELECT COUNT(*) FROM transactions WHERE id = ?", transactionId.toString()) == 1;
    }

    int suggestions(String kind) {
        return count("SELECT COUNT(*) FROM link_suggestions WHERE user_id = ? AND kind = ?", userId.toString(), kind);
    }

    int rejections(String kind) {
        return count("SELECT COUNT(*) FROM auto_link_rejections WHERE user_id = ? AND kind = ?", userId.toString(), kind);
    }

    /** {type, created_by} of the link the transaction is in, or null. */
    Map<String, Object> linkOf(UUID transactionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT l.id, l.type, l.created_by FROM transaction_links l "
                + "JOIN transaction_link_members m ON m.link_id = l.id WHERE m.transaction_id = ?", transactionId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The card-side credits of the card account. */
    List<Map<String, Object>> cardCredits() {
        return jdbc.queryForList("SELECT id, transaction_date, amount FROM transactions WHERE account_id = ? AND user_id = ?",
                card.getId().toString(), userId.toString());
    }
}
