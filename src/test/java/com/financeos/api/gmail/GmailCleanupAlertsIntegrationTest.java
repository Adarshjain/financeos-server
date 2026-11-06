package com.financeos.api.gmail;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Gmail cleanup's selection on real rows: the account's alert rows before the date that no statement has a line
 * for. A row a statement line sits on, a non-alert row, a row on or after the date and another account's row never count.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GmailCleanupAlertsIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private StatementPersistenceService statementPersistenceService;

    private ApiTestClient api;
    private User user;
    private Account bank;
    private Account other;

    @BeforeEach
    void setUp() throws Exception {
        String email = "gmail-cleanup-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
        bank = account("Cleanup Savings", "7483");
        other = account("Cleanup Other", "1122");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    @Test
    void onlyAlertRowsBeforeTheDateWithNoStatementLineAreSelected() {
        UUID unlinked = txn(bank, TransactionSource.gmail_transaction_alert, "2026-02-10");
        UUID onALine = txn(bank, TransactionSource.gmail_transaction_alert, "2026-02-11");
        txn(bank, TransactionSource.file_upload, "2026-02-12");
        txn(bank, TransactionSource.gmail_transaction_alert, "2026-03-01");
        txn(other, TransactionSource.gmail_transaction_alert, "2026-02-10");
        Statement statement = statement(bank);
        statementPersistenceService.linkTransactions(statement.getId(),
                List.of(new StatementPersistenceService.TxnLink(onALine, 0, null, null)));

        List<Transaction> selected = transactionRepository.findUnreconciledAlertsBeforeDate(bank.getId(), user.getId(),
                LocalDate.of(2026, 3, 1));

        assertThat(selected).extracting(Transaction::getId).containsExactly(unlinked);
    }

    private Account account(String name, String last4) throws Exception {
        return accountRepository.findById(api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name,
                "last4", last4, "openingBalance", 0, "financialPosition", "asset", "excludeFromNetAsset", false))).orElseThrow();
    }

    private Statement statement(Account account) {
        StatementDraft draft = new StatementDraft("bank_account", LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28),
                BigDecimal.ZERO, null, "HSBC", "xxxx7483", 1, 0, BigDecimal.ZERO, BigDecimal.ZERO, "balance_chain",
                new BigDecimal("100.0"), true, StatementVerdict.AUTO_INGEST, Map.of());
        return statementPersistenceService.createIfNew(user, account, StatementSource.file_upload, "feb.pdf", "sha-feb", draft)
                .orElseThrow();
    }

    private UUID txn(Account account, TransactionSource source, String date) {
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(account);
        t.setDate(LocalDate.parse(date));
        t.setAmount(new BigDecimal("100.00"));
        t.setType(TransactionType.DEBIT);
        t.setSource(source);
        t.setSourcedDescription("row " + date);
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        return transactionRepository.save(t).getId();
    }
}
