package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
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

/** lastTransactionDate on GET /accounts (batch query) and GET /accounts/{id}, over the real queries. */
@SpringBootTest
@AutoConfigureMockMvc
class AccountLastTransactionDateIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private User user;
    private UUID otherUserId;

    @BeforeEach
    void setUp() throws Exception {
        String email = "last-txn-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, otherUserId == null ? List.of(user.getId()) : List.of(user.getId(), otherUserId));
    }

    private UUID bank(String name) throws Exception {
        return api.create("/api/v1/accounts", Map.of("type", "bank_account", "name", name,
                "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private void save(User owner, UUID accountId, LocalDate date, boolean excluded) {
        Transaction t = new Transaction();
        t.setUser(owner);
        t.setAccount(accountRepository.findById(accountId).orElseThrow());
        t.setDate(date);
        t.setAmount(new BigDecimal("10"));
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.manual);
        t.setSourcedDescription("X");
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(excluded);
        transactionRepository.save(t);
    }

    private static JsonNode find(JsonNode array, UUID id) {
        for (JsonNode n : array) {
            if (id.toString().equals(n.path("id").asText())) {
                return n;
            }
        }
        throw new AssertionError("account not listed: " + id);
    }

    @Test
    void anAccountReportsItsLatestTransactionDateOnListAndSingleGet() throws Exception {
        UUID withTxns = bank("With transactions");
        save(user, withTxns, LocalDate.of(2026, 9, 1), false);
        save(user, withTxns, LocalDate.of(2026, 10, 5), false);
        save(user, withTxns, LocalDate.of(2026, 9, 20), false);

        assertEquals("2026-10-05", api.getJson("/api/v1/accounts/" + withTxns, 200).get("lastTransactionDate").asText());
        assertEquals("2026-10-05", find(api.getJson("/api/v1/accounts", 200), withTxns).get("lastTransactionDate").asText());
    }

    @Test
    void anAccountWithoutTransactionsReportsNull() throws Exception {
        UUID empty = bank("Empty");

        assertTrue(api.getJson("/api/v1/accounts/" + empty, 200).has("lastTransactionDate"));
        assertTrue(api.getJson("/api/v1/accounts/" + empty, 200).get("lastTransactionDate").isNull());
        assertTrue(find(api.getJson("/api/v1/accounts", 200), empty).get("lastTransactionDate").isNull());
    }

    @Test
    void excludedTransactionsCountBecauseTheBalanceCountsThem() throws Exception {
        UUID acc = bank("Excluded");
        save(user, acc, LocalDate.of(2026, 9, 1), false);
        save(user, acc, LocalDate.of(2026, 10, 9), true);

        assertEquals("2026-10-09", api.getJson("/api/v1/accounts/" + acc, 200).get("lastTransactionDate").asText());
    }

    @Test
    void eachAccountGetsItsOwnDateAndAnotherUsersTransactionsNeverLeak() throws Exception {
        UUID a = bank("A");
        UUID b = bank("B");
        save(user, a, LocalDate.of(2026, 9, 1), false);
        save(user, b, LocalDate.of(2026, 8, 2), false);

        String otherEmail = "last-txn-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        User other = userRepository.findByEmail(otherEmail).orElseThrow();
        otherUserId = other.getId();
        UUID theirs = accountRepository.save(newAccount(other)).getId();
        save(other, theirs, LocalDate.of(2026, 12, 31), false);

        JsonNode list = api.getJson("/api/v1/accounts", 200);
        assertEquals("2026-09-01", find(list, a).get("lastTransactionDate").asText());
        assertEquals("2026-08-02", find(list, b).get("lastTransactionDate").asText());
        assertEquals(2, list.size(), "only my accounts are listed");
    }

    private static Account newAccount(User owner) {
        Account account = new Account("Theirs", AccountType.bank_account);
        account.setUser(owner);
        return account;
    }
}
