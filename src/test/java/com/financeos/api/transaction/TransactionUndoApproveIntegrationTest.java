package com.financeos.api.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Approving transactions (batch-review with a clearing type) reports each row's prior review state
 * (approvedTransactions); POST /transactions/undo-approve puts it back unless the row changed since.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionUndoApproveIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;

    private Cookie session;
    private User user;
    private User otherUser;
    private Account account;
    private final List<UUID> transactionIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String email = "undo-approve-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "rulesPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        String otherEmail = "undo-approve-other-" + UUID.randomUUID() + "@example.test";
        authenticate(otherEmail, "rulesPass123!");
        otherUser = userRepository.findByEmail(otherEmail).orElseThrow();

        account = new Account();
        account.setName("Savings");
        account.setUser(user);
        account.setType(AccountType.bank_account);
        account = accountRepository.save(account);
    }

    /** Shared in-memory H2: remove every row this class created (see RuleUpdateCategoriesIntegrationTest). */
    @AfterEach
    void tearDown() {
        transactionRepository.deleteAllById(transactionIds);
        accountRepository.deleteById(account.getId());
        userRepository.deleteAllById(List.of(user.getId(), otherUser.getId()));
    }

    private Cookie authenticate(String email, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "email", email,
                        "password", password,
                        "inviteCode", "test-invite-code"))));

        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("FINANCEOS_SESSION");
    }

    private Transaction transaction(ReviewReason... reasons) {
        Transaction txn = new Transaction();
        txn.setUser(user);
        txn.setAccount(account);
        txn.setAmount(BigDecimal.valueOf(250));
        txn.setDate(LocalDate.parse("2026-10-01"));
        txn.setSource(TransactionSource.manual);
        txn.setType(TransactionType.DEBIT);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setReviewReasons(new HashSet<>(Arrays.asList(reasons)));
        Transaction saved = transactionRepository.save(txn);
        transactionIds.add(saved.getId());
        return saved;
    }

    private JsonNode review(String type, List<UUID> ids, String... reasons) throws Exception {
        return postJson("/api/v1/transactions/batch-review", Map.of(
                "transactionIds", ids, "reviewType", type, "reviewReasons", Arrays.asList(reasons)));
    }

    private JsonNode approve(String reason, UUID... ids) throws Exception {
        return review("MANUALLY_REVIEWED", Arrays.asList(ids), reason);
    }

    private ResultActions undoRaw(Object body) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions/undo-approve")
                .cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode undo(Object approved) throws Exception {
        return objectMapper.readTree(undoRaw(Map.of("approved", approved))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<Object> list(JsonNode n) {
        return objectMapper.convertValue(n, List.class);
    }

    private JsonNode postJson(String url, Object body) throws Exception {
        return objectMapper.readTree(mockMvc.perform(post(url)
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String reviewType(Transaction txn) {
        return jdbcTemplate.queryForObject("SELECT review_type FROM transactions WHERE id = ?",
                String.class, txn.getId().toString());
    }

    private Object reviewedAt(Transaction txn) {
        return jdbcTemplate.queryForObject("SELECT reviewed_at FROM transactions WHERE id = ?",
                Object.class, txn.getId().toString());
    }

    private void touch(Transaction t) throws Exception {
        Thread.sleep(20);
        Transaction r = transactionRepository.findById(t.getId()).orElseThrow();
        r.setSourcedDescription("edited by the user");
        transactionRepository.saveAndFlush(r);
    }

    private Set<String> reasons(Transaction txn) {
        return jdbcTemplate.queryForList(
                        "SELECT reason FROM transaction_review_reasons WHERE transaction_id = ?",
                        String.class, txn.getId().toString())
                .stream()
                .collect(Collectors.toSet());
    }

    @Test
    void approveThenUndo_restoresExactPriorState() throws Exception {
        Transaction p = transaction(ReviewReason.DUPLICATE_SUSPECT, ReviewReason.UNRECONCILED);
        Transaction a = transaction(ReviewReason.DUPLICATE_SUSPECT);

        var res = approve("DUPLICATE_SUSPECT", p.getId(), a.getId());
        assertEquals(2, res.get("approvedTransactions").size());
        assertFalse(res.has("verifiedRules"), "rules are no longer verified");
        assertEquals(p.getId().toString(), res.get("approvedTransactions").get(0).get("transactionId").asText());
        assertEquals("NEEDS_REVIEW", res.get("approvedTransactions").get(0).get("previousReviewType").asText());
        assertEquals(2, res.get("approvedTransactions").get(0).get("previousReviewReasons").size());
        assertEquals(Set.of(ReviewReason.UNRECONCILED.name()), reasons(p));
        assertEquals("NEEDS_REVIEW", reviewType(p));
        assertEquals("MANUALLY_REVIEWED", reviewType(a));

        var undone = undo(list(res.get("approvedTransactions")));
        assertEquals(2, undone.get("restoredTransactionIds").size());
        assertEquals(0, undone.get("skippedTransactionIds").size());

        assertEquals("NEEDS_REVIEW", reviewType(a));
        assertNull(reviewedAt(a));
        assertEquals(Set.of(ReviewReason.DUPLICATE_SUSPECT.name()), reasons(a));
        assertEquals("NEEDS_REVIEW", reviewType(p));
        assertEquals(Set.of(ReviewReason.DUPLICATE_SUSPECT.name(), ReviewReason.UNRECONCILED.name()), reasons(p));
    }

    @Test
    void approveFullyClearedRow_roundTrips() throws Exception {
        Transaction a = transaction(ReviewReason.DUPLICATE_SUSPECT);
        var res = approve("DUPLICATE_SUSPECT", a.getId());
        assertEquals("MANUALLY_REVIEWED", reviewType(a));
        assertNotNull(reviewedAt(a));

        var undone = undo(list(res.get("approvedTransactions")));
        assertEquals(1, undone.get("restoredTransactionIds").size());
        assertEquals("NEEDS_REVIEW", reviewType(a));
        assertNull(reviewedAt(a));
        assertEquals(Set.of(ReviewReason.DUPLICATE_SUSPECT.name()), reasons(a));
    }

    @Test
    void undo_skipsApprovedRowEditedAfterApprove_andKeepsTheEdit() throws Exception {
        Transaction edited = transaction(ReviewReason.DUPLICATE_SUSPECT);
        Transaction kept = transaction(ReviewReason.DUPLICATE_SUSPECT);
        var res = approve("DUPLICATE_SUSPECT", edited.getId(), kept.getId());
        touch(edited);

        var undone = undo(list(res.get("approvedTransactions")));
        assertEquals(List.of(kept.getId().toString()), List.of(undone.get("restoredTransactionIds").get(0).asText()));
        assertEquals(edited.getId().toString(), undone.get("skippedTransactionIds").get(0).asText());
        assertEquals("MANUALLY_REVIEWED", reviewType(edited));
        assertEquals("NEEDS_REVIEW", reviewType(kept));
    }

    @Test
    void undo_anotherUsersTransaction_isSkippedAndLeftAlone() throws Exception {
        Account otherAccount = new Account();
        otherAccount.setName("Other");
        otherAccount.setUser(otherUser);
        otherAccount.setType(AccountType.bank_account);
        otherAccount = accountRepository.save(otherAccount);
        Transaction t = new Transaction();
        t.setUser(otherUser);
        t.setAccount(otherAccount);
        t.setAmount(BigDecimal.TEN);
        t.setDate(LocalDate.parse("2026-10-01"));
        t.setSource(TransactionSource.manual);
        t.setType(TransactionType.DEBIT);
        t.setReviewType(ReviewType.NEEDS_REVIEW);
        t.setReviewReasons(new HashSet<>(Set.of(ReviewReason.DUPLICATE_SUSPECT)));
        Transaction theirs = transactionRepository.save(t);
        try {
            Transaction fresh = transactionRepository.findById(theirs.getId()).orElseThrow();
            var entry = Map.of("transactionId", theirs.getId().toString(), "previousReviewType", "NEEDS_REVIEW",
                    "previousReviewReasons", List.of("DUPLICATE_SUSPECT", "UNRECONCILED"),
                    "verifiedUpdatedAt", fresh.getUpdatedAt().toString());
            var undone = undo(List.of(entry));
            assertEquals(0, undone.get("restoredTransactionIds").size());
            assertEquals(1, undone.get("skippedTransactionIds").size());
            assertEquals(Set.of(ReviewReason.DUPLICATE_SUSPECT.name()), reasons(theirs));
        } finally {
            transactionRepository.deleteById(theirs.getId());
            accountRepository.deleteById(otherAccount.getId());
        }
    }

    @Test
    void undo_overTheCap_returns400() throws Exception {
        List<Object> tooManyApproved = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooManyApproved.add(Map.of("transactionId", UUID.randomUUID().toString(), "previousReviewType", "NEEDS_REVIEW",
                    "previousReviewReasons", List.of("UNRECONCILED"), "verifiedUpdatedAt", "2026-10-01T00:00:00Z"));
        }
        undoRaw(Map.of("approved", tooManyApproved)).andExpect(status().isBadRequest());
    }

    @Test
    void undo_entryWithoutTransactionId_returns400() throws Exception {
        Map<String, Object> bad = Map.of("previousReviewType", "NEEDS_REVIEW",
                "previousReviewReasons", List.of("UNRECONCILED"), "verifiedUpdatedAt", "2026-10-01T00:00:00Z");
        undoRaw(Map.of("approved", List.of(bad))).andExpect(status().isBadRequest());
    }

    @Test
    void nonClearingBatchReview_returnsEmptyApprovedTransactions() throws Exception {
        Transaction a = transaction(ReviewReason.DUPLICATE_SUSPECT);

        var res = review("NEEDS_REVIEW", List.of(a.getId()), "UNRECONCILED");

        assertEquals(0, res.get("approvedTransactions").size());
        assertEquals(Set.of(ReviewReason.DUPLICATE_SUSPECT.name(), ReviewReason.UNRECONCILED.name()), reasons(a));
    }
}
