package com.financeos.api.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
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

/** Opening balance on generic (wallet / cash) accounts: create, edit keeps, explicit null clears, balance = opening + sum. */
@SpringBootTest
@AutoConfigureMockMvc
class GenericOpeningBalanceIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private ApiTestClient api;
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        String email = "generic-opening-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        user = userRepository.findByEmail(email).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, List.of(user.getId()));
    }

    private Map<String, Object> body(String name) {
        return new HashMap<>(Map.of("type", "generic", "name", name, "financialPosition", "asset", "excludeFromNetAsset", false));
    }

    private JsonNode get(UUID id) throws Exception {
        return api.getJson("/api/v1/accounts/" + id, 200);
    }

    private void txn(UUID accountId, String amount, TransactionType type, LocalDate date) {
        Account a = accountRepository.findById(accountId).orElseThrow();
        Transaction t = new Transaction();
        t.setUser(user);
        t.setAccount(a);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(TransactionSource.manual);
        t.setCategoryKey("food");
        t.setReviewType(com.financeos.domain.transaction.ReviewType.AUTO_REVIEWED);
        t.setTransactionUnderMonitoring(false);
        t.setTransactionExcluded(false);
        transactionRepository.save(t);
    }

    @Test
    void createStoresItAndTheResponseReturnsIt() throws Exception {
        Map<String, Object> b = body("Test Wallet");
        b.put("openingBalance", 500);
        UUID id = api.create("/api/v1/accounts", b);
        assertThat(get(id).get("openingBalance").decimalValue()).isEqualByComparingTo("500");
        assertThat(get(id).get("balance").decimalValue()).isEqualByComparingTo("500");
    }

    @Test
    void createWithoutItLeavesItNull() throws Exception {
        UUID id = api.create("/api/v1/accounts", body("Test Wallet"));
        assertThat(get(id).get("openingBalance").isNull()).isTrue();
    }

    @Test
    void balanceIsOpeningPlusTheSignedSumOfTransactions() throws Exception {
        Map<String, Object> b = body("Test Wallet");
        b.put("openingBalance", 500);
        UUID id = api.create("/api/v1/accounts", b);
        txn(id, "120.00", TransactionType.DEBIT, LocalDate.of(2026, 11, 2));
        txn(id, "20.00", TransactionType.CREDIT, LocalDate.of(2026, 11, 3));
        assertThat(get(id).get("balance").decimalValue()).isEqualByComparingTo("400");
    }

    @Test
    void anEditThatOmitsItKeepsTheStoredValue() throws Exception {
        Map<String, Object> b = body("Test Wallet");
        b.put("openingBalance", 500);
        UUID id = api.create("/api/v1/accounts", b);
        Map<String, Object> edit = body("Renamed Wallet");
        api.putJson("/api/v1/accounts/" + id, edit, 200);
        JsonNode after = get(id);
        assertThat(after.get("name").asText()).isEqualTo("Renamed Wallet");
        assertThat(after.get("openingBalance").decimalValue()).isEqualByComparingTo("500");
    }

    @Test
    void anEditCanChangeItAndAnExplicitNullClearsIt() throws Exception {
        Map<String, Object> b = body("Test Wallet");
        b.put("openingBalance", 500);
        UUID id = api.create("/api/v1/accounts", b);

        Map<String, Object> change = body("Test Wallet");
        change.put("openingBalance", 250);
        api.putJson("/api/v1/accounts/" + id, change, 200);
        assertThat(get(id).get("openingBalance").decimalValue()).isEqualByComparingTo("250");

        Map<String, Object> clear = body("Test Wallet");
        clear.put("openingBalance", null);
        api.putJson("/api/v1/accounts/" + id, clear, 200);
        assertThat(get(id).get("openingBalance").isNull()).isTrue();
        assertThat(get(id).get("balance").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void anAccountCreatedWithoutItCanGetOneOnEdit() throws Exception {
        UUID id = api.create("/api/v1/accounts", body("Test Wallet"));
        Map<String, Object> edit = body("Test Wallet");
        edit.put("openingBalance", 75);
        api.putJson("/api/v1/accounts/" + id, edit, 200);
        assertThat(get(id).get("openingBalance").decimalValue()).isEqualByComparingTo("75");
    }

    @Test
    void theAccountListReturnsItToo() throws Exception {
        Map<String, Object> b = body("Test Wallet");
        b.put("openingBalance", 500);
        UUID id = api.create("/api/v1/accounts", b);
        JsonNode list = api.getJson("/api/v1/accounts", 200);
        JsonNode found = null;
        for (JsonNode a : list) if (a.get("id").asText().equals(id.toString())) found = a;
        assertThat(found).isNotNull();
        assertThat(found.get("openingBalance").decimalValue()).isEqualByComparingTo("500");
        assertThat(found.get("balance").decimalValue()).isEqualByComparingTo("500");
    }

    @Test
    void aNonNumericOpeningBalanceIsRefusedWith400OnCreateAndEdit() throws Exception {
        Map<String, Object> bad = body("Test Wallet");
        bad.put("openingBalance", "lots");
        assertThat(api.post("/api/v1/accounts", bad).getStatus()).isEqualTo(400);

        UUID id = api.create("/api/v1/accounts", body("Test Wallet"));
        Map<String, Object> edit = body("Test Wallet");
        edit.put("openingBalance", "lots");
        api.putJson("/api/v1/accounts/" + id, edit, 400);
        assertThat(get(id).get("openingBalance").isNull()).isTrue();
    }
}
