package com.financeos.domain.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /api/v1/transactions/review/duplicates: flagged rows with the twin the duplicate matcher finds, per user. */
@SpringBootTest
@AutoConfigureMockMvc
class DuplicateReviewIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionTemplate tx;

    private final List<UUID> users = new ArrayList<>();
    private ApiTestClient api;
    private Account account;

    @BeforeEach
    void setUp() throws Exception {
        String email = "dup-review-" + UUID.randomUUID() + "@example.test";
        api = ApiTestClient.signUp(mockMvc, mapper, email);
        users.add(userRepository.findByEmail(email).orElseThrow().getId());
        account = account(api, "Dup Bank One");
    }

    @AfterEach
    void tearDown() {
        UserDataCleanup.deleteUsers(jdbc, users);
    }

    private Account account(ApiTestClient client, String name) throws Exception {
        return accountRepository.findById(client.create("/api/v1/accounts", Map.of("type", "bank_account",
                "name", name, "last4", "1234", "openingBalance", 100, "financialPosition", "asset",
                "excludeFromNetAsset", false))).orElseThrow();
    }

    private UUID txn(Account owner, LocalDate date, String amount, String description, TransactionSource source, boolean flagged) {
        Transaction t = new Transaction(owner, date, new BigDecimal(amount), description, source, TransactionType.DEBIT, false, false);
        t.setUser(owner.getUser());
        t.setSourcedDescription(description);
        if (flagged) {
            t.setReviewType(ReviewType.NEEDS_REVIEW);
            t.setReviewReasons(new java.util.HashSet<>(Set.of(ReviewReason.DUPLICATE_SUSPECT)));
        }
        return tx.execute(s -> transactionRepository.save(t).getId());
    }

    private JsonNode list() throws Exception {
        return api.getJson("/api/v1/transactions/review/duplicates?size=10", 200);
    }

    @Test
    void flaggedRowComesWithItsTwinAndAnUnmatchedOneHasNone() throws Exception {
        UUID flagged = txn(account, DAY, "250.00", "Corner Cafe", TransactionSource.manual, true);
        UUID twin = txn(account, DAY, "250.00", "Corner Cafe Pvt Ltd", TransactionSource.file_upload, false);
        UUID lonely = txn(account, DAY.plusDays(1), "99.00", "Solo Shop", TransactionSource.manual, true);
        // same description and amount but another day, and another amount: not twins
        txn(account, DAY.minusDays(1), "250.00", "Corner Cafe", TransactionSource.manual, false);
        txn(account, DAY, "251.00", "Corner Cafe", TransactionSource.manual, false);

        JsonNode page = list();

        assertEquals(2, page.get("totalElements").asInt());
        JsonNode first = page.get("content").get(0);
        JsonNode second = page.get("content").get(1);
        assertEquals(lonely.toString(), first.get("transaction").get("id").asText());
        assertTrue(first.get("twin").isNull());
        assertEquals(flagged.toString(), second.get("transaction").get("id").asText());
        assertEquals(twin.toString(), second.get("twin").get("id").asText());
        assertEquals("Dup Bank One", second.get("twin").get("accountName").asText());
        assertEquals("file_upload", second.get("twin").get("source").asText());
        assertEquals("Corner Cafe Pvt Ltd", second.get("twin").get("description").asText());
        assertEquals(0, new BigDecimal("250").compareTo(second.get("twin").get("amount").decimalValue()));
        assertEquals(DAY.toString(), second.get("transaction").get("date").asText());
    }

    @Test
    void unflaggedTwinIsPreferredOverAnotherFlaggedRow() throws Exception {
        UUID a = txn(account, DAY, "40.00", "Metro Card", TransactionSource.manual, true);
        UUID b = txn(account, DAY, "40.00", "Metro Card", TransactionSource.manual, true);
        UUID clean = txn(account, DAY, "40.00", "Metro Card", TransactionSource.manual, false);

        JsonNode page = list();

        assertEquals(2, page.get("totalElements").asInt());
        for (JsonNode item : page.get("content")) {
            assertEquals(clean.toString(), item.get("twin").get("id").asText());
            assertTrue(Set.of(a.toString(), b.toString()).contains(item.get("transaction").get("id").asText()));
        }
    }

    @Test
    void pagesAreServerSide() throws Exception {
        for (int i = 0; i < 3; i++) {
            txn(account, DAY.plusDays(i), "10.00", "Item " + i, TransactionSource.manual, true);
        }

        JsonNode page = api.getJson("/api/v1/transactions/review/duplicates?size=2&page=1", 200);

        assertEquals(3, page.get("totalElements").asInt());
        assertEquals(1, page.get("content").size());
    }

    @Test
    void anotherUsersFlaggedRowsAndTwinsAreNeverShown() throws Exception {
        String otherEmail = "dup-review-other-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        users.add(userRepository.findByEmail(otherEmail).orElseThrow().getId());
        Account otherAccount = account(other, "Other Bank");
        UUID mine = txn(account, DAY, "75.00", "Shared Name", TransactionSource.manual, true);
        txn(otherAccount, DAY, "75.00", "Shared Name", TransactionSource.manual, true);
        txn(otherAccount, DAY, "75.00", "Shared Name", TransactionSource.manual, false);

        JsonNode page = list();

        assertEquals(1, page.get("totalElements").asInt());
        assertEquals(mine.toString(), page.get("content").get(0).get("transaction").get("id").asText());
        assertTrue(page.get("content").get(0).get("twin").isNull());
        JsonNode theirs = other.getJson("/api/v1/transactions/review/duplicates", 200);
        assertEquals(1, theirs.get("totalElements").asInt());
        assertTrue(!theirs.get("content").get(0).get("transaction").get("id").asText().equals(mine.toString()));
    }

    @Test
    void idsFilterReturnsOnlyThoseRowsAndIgnoresAnotherUsersId() throws Exception {
        String otherEmail = "dup-review-ids-" + UUID.randomUUID() + "@example.test";
        ApiTestClient other = ApiTestClient.signUp(mockMvc, mapper, otherEmail);
        users.add(userRepository.findByEmail(otherEmail).orElseThrow().getId());
        Account otherAccount = account(other, "Other Bank");
        UUID a = txn(account, DAY, "11.00", "Alpha", TransactionSource.manual, true);
        UUID b = txn(account, DAY.plusDays(1), "12.00", "Beta", TransactionSource.manual, true);
        txn(account, DAY.plusDays(2), "13.00", "Gamma", TransactionSource.manual, true);
        UUID unflagged = txn(account, DAY.plusDays(3), "14.00", "Delta", TransactionSource.manual, false);
        UUID theirs = txn(otherAccount, DAY, "15.00", "Theirs", TransactionSource.manual, true);

        JsonNode page = api.getJson("/api/v1/transactions/review/duplicates?size=10&ids=" + a + "," + b + ","
                + unflagged + "," + theirs, 200);

        assertEquals(2, page.get("totalElements").asInt());
        Set<String> got = new java.util.HashSet<>();
        for (JsonNode item : page.get("content")) {
            got.add(item.get("transaction").get("id").asText());
        }
        assertEquals(Set.of(a.toString(), b.toString()), got);
        JsonNode paged = api.getJson("/api/v1/transactions/review/duplicates?size=1&page=1&ids=" + a + "," + b, 200);
        assertEquals(2, paged.get("totalElements").asInt());
        assertEquals(1, paged.get("content").size());
    }

    @Test
    void tooManyIdsIsABadRequest() throws Exception {
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            q.append(i == 0 ? "" : ",").append(UUID.randomUUID());
        }
        api.getJson("/api/v1/transactions/review/duplicates?ids=" + q, 400);
    }

    @Test
    void pageSizeIsCappedAt200() throws Exception {
        txn(account, DAY, "10.00", "Capped", TransactionSource.manual, true);

        JsonNode page = api.getJson("/api/v1/transactions/review/duplicates?size=5000", 200);

        assertEquals(200, page.get("size").asInt());
        assertEquals(1, page.get("totalElements").asInt());
    }
}
