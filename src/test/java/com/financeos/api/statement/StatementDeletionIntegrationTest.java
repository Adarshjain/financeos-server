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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Statement deletion over real persistence: the preview and the delete agree, the statement's own
 * rows and line links go while a transaction it only matched stays, the same file can be ingested
 * again afterwards, and anyone else's account or statement answers 404 without touching anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StatementDeletionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementPersistenceService statementPersistenceService;

    private ApiTestClient api;
    private ApiTestClient otherApi;
    private UUID userId;
    private UUID otherUserId;
    private UUID accountId;
    private UUID secondAccountId;
    private UUID statementId;
    private UUID createdRow1;
    private UUID createdRow2;
    private UUID matchedManualRow;

    @BeforeEach
    void setUp() throws Exception {
        String email = "stmt-delete-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        userId = userRepository.findByEmail(email).orElseThrow().getId();
        String otherEmail = "stmt-delete-other-" + UUID.randomUUID() + "@example.test";
        otherApi = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        otherUserId = userRepository.findByEmail(otherEmail).orElseThrow().getId();

        accountId = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "HSBC Savings", "last4", "7483",
                "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));
        secondAccountId = api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", "Other Savings",
                "last4", "1111", "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false));

        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        Statement statement = statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                "27FEB2026.pdf", "sha-feb", draft()).orElseThrow();
        statementId = statement.getId();
        account.setLastStatementDate(LocalDate.of(2026, 2, 27));
        accountRepository.save(account);

        createdRow1 = save(user, account, TransactionSource.file_upload, "IMPS 605409990907", "1.00", TransactionType.CREDIT);
        createdRow2 = save(user, account, TransactionSource.file_upload, "ONE MOBIKWIK SYSTEMS", "10000.00", TransactionType.CREDIT);
        matchedManualRow = save(user, account, TransactionSource.manual, "Salary", "305705.74", TransactionType.CREDIT);
        statementPersistenceService.linkTransactions(statementId, List.of(
                new StatementPersistenceService.TxnLink(createdRow1, 0, new BigDecimal("1.00"), true),
                new StatementPersistenceService.TxnLink(createdRow2, 1, new BigDecimal("10000.00"), true),
                new StatementPersistenceService.TxnLink(matchedManualRow, 2, null, null)));
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(userId, otherUserId));
    }

    @Test
    void previewThenDeleteRemovesTheStatementAndItsRowsAndKeepsWhatItMatched() throws Exception {
        JsonNode preview = api.getJson(path(accountId, statementId) + "/deletion-preview", 200);
        assertEquals(2, preview.get("transactionsToDelete").asInt());
        assertEquals(1, preview.get("transactionsKept").asInt());
        assertEquals(0, preview.get("linkedToRecords").asInt());
        assertEquals(0, preview.get("alertsToReopen").asInt());

        JsonNode outcome = api.deleteJson(path(accountId, statementId), 200);
        assertEquals(2, outcome.get("transactionsDeleted").asInt());
        assertEquals(1, outcome.get("transactionsKept").asInt());

        assertEquals(0, api.getJson("/api/v1/accounts/" + accountId + "/statements", 200).size());
        assertEquals(0, count("SELECT COUNT(*) FROM statements WHERE id = ?", statementId));
        assertEquals(0, count("SELECT COUNT(*) FROM statement_transactions WHERE statement_id = ?", statementId));
        assertEquals(0, count("SELECT COUNT(*) FROM transactions WHERE id IN (?, ?)", createdRow1, createdRow2));
        assertEquals(1, count("SELECT COUNT(*) FROM transactions WHERE id = ?", matchedManualRow));
        assertNull(accountRepository.findById(accountId).orElseThrow().getLastStatementDate());
    }

    @Test
    void theSameFileCanBeIngestedAgainAfterDeletion() throws Exception {
        User user = userRepository.findById(userId).orElseThrow();
        Account account = accountRepository.findById(accountId).orElseThrow();
        assertTrue(statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                "27FEB2026.pdf", "sha-feb", draft()).isEmpty(), "blocked while the statement exists");

        api.deleteJson(path(accountId, statementId), 200);

        assertTrue(statementPersistenceService.createIfNew(user, account, StatementSource.file_upload,
                "27FEB2026.pdf", "sha-feb", draft()).isPresent(), "accepted once it is deleted");
    }

    @Test
    void anotherUserGetsNotFoundAndNothingChanges() throws Exception {
        assertEquals(404, otherApi.get(path(accountId, statementId) + "/deletion-preview").getStatus());
        otherApi.deleteJson(path(accountId, statementId), 404);

        assertUntouched();
    }

    @Test
    void statementAddressedThroughAnotherOfTheUsersAccountsIsNotFound() throws Exception {
        assertEquals(404, api.get(path(secondAccountId, statementId) + "/deletion-preview").getStatus());
        api.deleteJson(path(secondAccountId, statementId), 404);

        assertUntouched();
    }

    @Test
    void unknownStatementIsNotFound() throws Exception {
        api.deleteJson(path(accountId, UUID.randomUUID()), 404);
        assertEquals(404, api.get(path(UUID.randomUUID(), statementId) + "/deletion-preview").getStatus());

        assertUntouched();
    }

    private void assertUntouched() {
        assertEquals(1, count("SELECT COUNT(*) FROM statements WHERE id = ?", statementId));
        assertEquals(3, count("SELECT COUNT(*) FROM statement_transactions WHERE statement_id = ?", statementId));
        assertEquals(3, count("SELECT COUNT(*) FROM transactions WHERE id IN (?, ?, ?)",
                createdRow1, createdRow2, matchedManualRow));
    }

    private static String path(UUID account, UUID statement) {
        return "/api/v1/accounts/" + account + "/statements/" + statement;
    }

    private int count(String sql, Object... ids) {
        Object[] args = new Object[ids.length];
        for (int i = 0; i < ids.length; i++) {
            args[i] = ids[i].toString();
        }
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private UUID save(User user, Account account, TransactionSource source, String description, String amount,
                      TransactionType type) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setDate(LocalDate.of(2026, 2, 25));
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(source);
        t.setSourcedDescription(description);
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        return transactionRepository.save(t).getId();
    }

    private static StatementDraft draft() {
        return new StatementDraft("bank_account", LocalDate.of(2026, 2, 23), LocalDate.of(2026, 2, 27),
                BigDecimal.ZERO, new BigDecimal("327690.89"), "HSBC", "043-267483-006", 3, 0,
                new BigDecimal("11015.85"), new BigDecimal("338706.74"), "balance_chain", new BigDecimal("100.0"),
                true, StatementVerdict.AUTO_INGEST, Map.of());
    }
}
