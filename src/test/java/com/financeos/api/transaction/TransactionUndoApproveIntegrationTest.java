package com.financeos.api.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.MatchType;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Approving a transaction verifies its unverified rule and reports the other transactions that
 * cleared (batch-review verifiedRules); POST /rules/{id}/unverify rolls that back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionUndoApproveIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private CategoryRuleRepository categoryRuleRepository;
    @Autowired private TransactionRepository transactionRepository;

    private Cookie session;
    private User user;
    private User otherUser;
    private Account account;
    private Category food;
    private Category otherFood;
    private final List<UUID> ruleIds = new ArrayList<>();
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

        food = categoryRepository.save(new Category("Food", user));
        otherFood = categoryRepository.save(new Category("Food", otherUser));
    }

    /** Shared in-memory H2: remove every row this class created (see RuleUpdateCategoriesIntegrationTest). */
    @AfterEach
    void tearDown() {
        transactionRepository.deleteAllById(transactionIds);
        categoryRuleRepository.deleteAllById(ruleIds);
        categoryRepository.deleteAllById(List.of(food.getId(), otherFood.getId()));
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

    private CategoryRule rule(User owner, Category category, boolean verified) {
        CategoryRule rule = new CategoryRule();
        rule.setUser(owner);
        rule.setMerchantKey("merchant-" + UUID.randomUUID());
        rule.setMatchType(MatchType.MERCHANT_KEY);
        rule.setSource("LLM");
        rule.setVerified(verified);
        rule.setCategories(new HashSet<>(Set.of(category)));
        CategoryRule saved = categoryRuleRepository.save(rule);
        ruleIds.add(saved.getId());
        return saved;
    }

    private Transaction linkedTransaction(CategoryRule rule, ReviewReason... reasons) {
        Transaction txn = new Transaction();
        txn.setUser(user);
        txn.setAccount(account);
        txn.setAmount(BigDecimal.valueOf(250));
        txn.setDate(LocalDate.parse("2026-10-01"));
        txn.setSource(TransactionSource.manual);
        txn.setType(TransactionType.DEBIT);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setReviewReasons(new HashSet<>(Arrays.asList(reasons)));
        txn.setAppliedRule(rule);
        txn.setCategories(new HashSet<>(Set.of(food)));
        Transaction saved = transactionRepository.save(txn);
        transactionIds.add(saved.getId());
        return saved;
    }

    private com.fasterxml.jackson.databind.JsonNode review(String type, List<UUID> ids, String... reasons) throws Exception {
        return postJson("/api/v1/transactions/batch-review", Map.of(
                "transactionIds", ids, "reviewType", type, "reviewReasons", Arrays.asList(reasons)));
    }

    private com.fasterxml.jackson.databind.JsonNode approve(UUID... ids) throws Exception {
        return review("MANUALLY_REVIEWED", Arrays.asList(ids), "CATEGORY_UNVERIFIED");
    }

    private ResultActions undoRaw(Object body) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions/undo-approve")
                .cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private com.fasterxml.jackson.databind.JsonNode undo(Object approved, Object rules) throws Exception {
        return objectMapper.readTree(undoRaw(Map.of("approved", approved, "rules", rules))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<Object> list(com.fasterxml.jackson.databind.JsonNode n) {
        return objectMapper.convertValue(n, List.class);
    }

    private List<Object> ruleEntry(com.fasterxml.jackson.databind.JsonNode change) {
        return List.of(Map.of("ruleId", change.get("ruleId").asText(), "restore", list(change.get("clearedTransactions"))));
    }

    private Transaction other(User owner, Account acct, CategoryRule rule, Category cat) {
        Transaction t = new Transaction();
        t.setUser(owner);
        t.setAccount(acct);
        t.setAmount(BigDecimal.TEN);
        t.setDate(LocalDate.parse("2026-10-01"));
        t.setSource(TransactionSource.manual);
        t.setType(TransactionType.DEBIT);
        t.setReviewType(ReviewType.NEEDS_REVIEW);
        t.setReviewReasons(new HashSet<>(Set.of(ReviewReason.CATEGORY_UNVERIFIED)));
        t.setAppliedRule(rule);
        t.setCategories(new HashSet<>(Set.of(cat)));
        return transactionRepository.save(t);
    }

    private com.fasterxml.jackson.databind.JsonNode postJson(String url, Object body) throws Exception {
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

    private ResultActions unverify(CategoryRule rule, Object ids) throws Exception {
        return mockMvc.perform(post("/api/v1/rules/{id}/unverify", rule.getId())
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Collections.singletonMap("transactionIds", ids))));
    }

    private boolean isVerified(CategoryRule rule) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT verified FROM category_rules WHERE id = ?", Boolean.class, rule.getId().toString()));
    }

    private Set<String> reasons(Transaction txn) {
        return jdbcTemplate.queryForList(
                        "SELECT reason FROM transaction_review_reasons WHERE transaction_id = ?",
                        String.class, txn.getId().toString())
                .stream()
                .collect(Collectors.toSet());
    }

    @Test
    void approveThenUndo_restoresExactPriorStateOfApprovedAndRuleRows_andUnverifiesRule() throws Exception {
        CategoryRule rule = rule(user, food, false);
        Transaction p = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED, ReviewReason.UNRECONCILED);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction ruleOnly = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction ruleTwo = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED, ReviewReason.DUPLICATE_SUSPECT);

        var res = approve(p.getId(), a.getId());
        assertEquals(2, res.get("approvedTransactions").size());
        assertEquals(p.getId().toString(), res.get("approvedTransactions").get(0).get("transactionId").asText());
        assertEquals("NEEDS_REVIEW", res.get("approvedTransactions").get(0).get("previousReviewType").asText());
        assertEquals(2, res.get("approvedTransactions").get(0).get("previousReviewReasons").size());
        assertEquals(2, res.get("verifiedRules").get(0).get("clearedTransactions").size());
        assertTrue(isVerified(rule));
        assertEquals(Set.of(ReviewReason.UNRECONCILED.name()), reasons(p));
        assertEquals("NEEDS_REVIEW", reviewType(p));
        assertEquals("MANUALLY_REVIEWED", reviewType(a));
        assertEquals("AUTO_REVIEWED", reviewType(ruleOnly));

        var undone = undo(list(res.get("approvedTransactions")), ruleEntry(res.get("verifiedRules").get(0)));
        assertEquals(4, undone.get("restoredTransactionIds").size());
        assertEquals(0, undone.get("skippedTransactionIds").size());

        assertFalse(isVerified(rule));
        assertEquals("NEEDS_REVIEW", reviewType(a));
        assertNull(reviewedAt(a));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(a));
        assertEquals("NEEDS_REVIEW", reviewType(p));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name(), ReviewReason.UNRECONCILED.name()), reasons(p));
        assertEquals("NEEDS_REVIEW", reviewType(ruleOnly));
        assertNull(reviewedAt(ruleOnly));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(ruleOnly));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name(), ReviewReason.DUPLICATE_SUSPECT.name()), reasons(ruleTwo));
    }

    @Test
    void approveFullyClearedRowWithItsRuleVerified_roundTrips() throws Exception {
        CategoryRule rule = rule(user, food, false);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        var res = approve(a.getId());
        assertEquals("MANUALLY_REVIEWED", reviewType(a));
        assertNotNull(reviewedAt(a));
        assertEquals(0, res.get("verifiedRules").get(0).get("clearedTransactions").size());

        var undone = undo(list(res.get("approvedTransactions")), ruleEntry(res.get("verifiedRules").get(0)));
        assertEquals(1, undone.get("restoredTransactionIds").size());
        assertEquals("NEEDS_REVIEW", reviewType(a));
        assertNull(reviewedAt(a));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(a));
        assertFalse(isVerified(rule));
    }

    @Test
    void sameRequestRowClearedByVerifyThenApproved_getsItsOriginalStateBack() throws Exception {
        // b has a second reason so it is approved after a's verify already cleared its CATEGORY_UNVERIFIED.
        CategoryRule rule = rule(user, food, false);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction b = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED, ReviewReason.UNRECONCILED);
        var res = review("MANUALLY_REVIEWED", List.of(a.getId(), b.getId()), "CATEGORY_UNVERIFIED", "UNRECONCILED");
        assertEquals(2, res.get("approvedTransactions").size());
        assertEquals("MANUALLY_REVIEWED", reviewType(b));

        undo(list(res.get("approvedTransactions")), ruleEntry(res.get("verifiedRules").get(0)));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name(), ReviewReason.UNRECONCILED.name()), reasons(b));
        assertEquals("NEEDS_REVIEW", reviewType(b));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(a));
    }

    @Test
    void undo_skipsApprovedRowEditedAfterApprove_andKeepsTheEdit() throws Exception {
        CategoryRule rule = rule(user, food, true);
        Transaction edited = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction kept = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        var res = approve(edited.getId(), kept.getId());
        touch(edited);

        var undone = undo(list(res.get("approvedTransactions")), List.of());
        assertEquals(List.of(kept.getId().toString()), List.of(undone.get("restoredTransactionIds").get(0).asText()));
        assertEquals(edited.getId().toString(), undone.get("skippedTransactionIds").get(0).asText());
        assertEquals("MANUALLY_REVIEWED", reviewType(edited));
        assertEquals("NEEDS_REVIEW", reviewType(kept));
    }

    @Test
    void undo_skipsRuleRowEditedAfterApprove_andRowMovedOffTheRule() throws Exception {
        CategoryRule rule = rule(user, food, false);
        CategoryRule elsewhere = rule(user, food, true);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction edited = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction moved = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction fine = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        var res = approve(a.getId());
        touch(edited);
        Transaction m = transactionRepository.findById(moved.getId()).orElseThrow();
        m.setAppliedRule(elsewhere);
        transactionRepository.saveAndFlush(m);

        var undone = undo(list(res.get("approvedTransactions")), ruleEntry(res.get("verifiedRules").get(0)));
        assertEquals(2, undone.get("restoredTransactionIds").size());
        assertEquals(2, undone.get("skippedTransactionIds").size());
        assertFalse(isVerified(rule));
        assertEquals("AUTO_REVIEWED", reviewType(edited));
        assertEquals("AUTO_REVIEWED", reviewType(moved));
        assertEquals("NEEDS_REVIEW", reviewType(fine));
    }

    @Test
    void undo_anotherUsersTransaction_isSkippedAndLeftAlone() throws Exception {
        Account otherAccount = new Account();
        otherAccount.setName("Other");
        otherAccount.setUser(otherUser);
        otherAccount.setType(AccountType.bank_account);
        otherAccount = accountRepository.save(otherAccount);
        Transaction theirs = other(otherUser, otherAccount, null, otherFood);
        try {
            Transaction t = transactionRepository.findById(theirs.getId()).orElseThrow();
            var entry = Map.of("transactionId", theirs.getId().toString(), "previousReviewType", "NEEDS_REVIEW",
                    "previousReviewReasons", List.of("CATEGORY_UNVERIFIED", "UNRECONCILED"),
                    "verifiedUpdatedAt", t.getUpdatedAt().toString());
            var undone = undo(List.of(entry), List.of());
            assertEquals(0, undone.get("restoredTransactionIds").size());
            assertEquals(1, undone.get("skippedTransactionIds").size());
            assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(theirs));
        } finally {
            transactionRepository.deleteById(theirs.getId());
            accountRepository.deleteById(otherAccount.getId());
        }
    }

    @Test
    void undo_anotherUsersRule_rejectsTheWholeRequestAndChangesNothing() throws Exception {
        CategoryRule mine = rule(user, food, false);
        CategoryRule theirs = rule(otherUser, otherFood, true);
        Transaction a = linkedTransaction(mine, ReviewReason.CATEGORY_UNVERIFIED);
        var res = approve(a.getId());

        undoRaw(Map.of("approved", list(res.get("approvedTransactions")), "rules", List.of(
                Map.of("ruleId", mine.getId().toString(), "restore", List.of()),
                Map.of("ruleId", theirs.getId().toString(), "restore", List.of()))))
                .andExpect(status().isNotFound());

        assertTrue(isVerified(mine));
        assertTrue(isVerified(theirs));
        assertEquals("MANUALLY_REVIEWED", reviewType(a));
    }

    @Test
    void undo_unknownRule_returns404AndChangesNothing() throws Exception {
        CategoryRule rule = rule(user, food, true);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        var res = approve(a.getId());

        undoRaw(Map.of("approved", list(res.get("approvedTransactions")), "rules", List.of(
                Map.of("ruleId", UUID.randomUUID().toString(), "restore", List.of()))))
                .andExpect(status().isNotFound());

        assertEquals("MANUALLY_REVIEWED", reviewType(a));
    }

    @Test
    void undo_overTheCaps_returns400() throws Exception {
        List<Object> tooManyApproved = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            tooManyApproved.add(Map.of("transactionId", UUID.randomUUID().toString(), "previousReviewType", "NEEDS_REVIEW",
                    "previousReviewReasons", List.of("UNRECONCILED"), "verifiedUpdatedAt", "2026-10-01T00:00:00Z"));
        }
        undoRaw(Map.of("approved", tooManyApproved, "rules", List.of())).andExpect(status().isBadRequest());

        List<Object> tooManyRules = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooManyRules.add(Map.of("ruleId", UUID.randomUUID().toString(), "restore", List.of()));
        }
        undoRaw(Map.of("approved", List.of(), "rules", tooManyRules)).andExpect(status().isBadRequest());

        // total restore rows over 20000 across rules (each rule within its own cap)
        Object entry = Map.of("transactionId", UUID.randomUUID().toString(), "previousReviewType", "NEEDS_REVIEW",
                "previousReviewReasons", List.of("CATEGORY_UNVERIFIED"), "verifiedUpdatedAt", "2026-10-01T00:00:00Z");
        List<Object> rows = Collections.nCopies(10001, entry);
        undoRaw(Map.of("approved", List.of(), "rules", List.of(
                Map.of("ruleId", UUID.randomUUID().toString(), "restore", rows),
                Map.of("ruleId", UUID.randomUUID().toString(), "restore", rows)))).andExpect(status().isBadRequest());
    }

    @Test
    void undo_rowInBothApprovedAndRuleRestore_isReportedOnceAsRestored() throws Exception {
        CategoryRule rule = rule(user, food, false);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        var res = approve(a.getId());
        List<Object> approved = list(res.get("approvedTransactions"));
        var undone = undo(approved, List.of(Map.of("ruleId", rule.getId().toString(), "restore", approved)));
        assertEquals(1, undone.get("restoredTransactionIds").size());
        assertEquals(0, undone.get("skippedTransactionIds").size());
        assertEquals("NEEDS_REVIEW", reviewType(a));
    }

    @Test
    void undo_entryWithoutTransactionId_returns400() throws Exception {
        Map<String, Object> bad = Map.of("previousReviewType", "NEEDS_REVIEW",
                "previousReviewReasons", List.of("UNRECONCILED"), "verifiedUpdatedAt", "2026-10-01T00:00:00Z");
        undoRaw(Map.of("approved", List.of(bad), "rules", List.of())).andExpect(status().isBadRequest());
        CategoryRule rule = rule(user, food, true);
        undoRaw(Map.of("approved", List.of(), "rules", List.of(
                Map.of("ruleId", rule.getId().toString(), "restore", List.of(bad))))).andExpect(status().isBadRequest());
        assertTrue(isVerified(rule));
    }

    @Test
    void nonClearingBatchReview_returnsEmptyApprovedTransactions() throws Exception {
        CategoryRule rule = rule(user, food, true);
        Transaction a = linkedTransaction(rule, ReviewReason.UNRECONCILED);
        Transaction b = linkedTransaction(rule, ReviewReason.UNRECONCILED);
        postJson("/api/v1/transactions/batch-review", Map.of("transactionIds", List.of(a.getId()),
                "reviewType", "MANUALLY_REVIEWED", "reviewReasons", List.of("UNRECONCILED")));

        var res = review("NEEDS_REVIEW", List.of(a.getId()), "UNRECONCILED");
        assertEquals(0, res.get("approvedTransactions").size());
        assertEquals(1, res.get("succeededIds").size());
        assertEquals("NEEDS_REVIEW", reviewType(a));
        assertEquals(Set.of(ReviewReason.UNRECONCILED.name()), reasons(b));
    }

    @Test
    void oldClientPath_needsReviewBatchAndUnverifyWithTransactionIds_stillWorks() throws Exception {
        CategoryRule rule = rule(user, food, false);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction o = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        var res = approve(a.getId());
        var change = res.get("verifiedRules").get(0);
        assertEquals(1, change.get("clearedTransactionIds").size());

        review("NEEDS_REVIEW", List.of(a.getId()), "CATEGORY_UNVERIFIED");
        unverify(rule, List.of(o.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredTransactionIds.length()").value(1));

        assertFalse(isVerified(rule));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(a));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(o));
        assertEquals("NEEDS_REVIEW", reviewType(o));
    }

    @Test
    void approveThenUndo_ruleWithMoreThan1000Rows_restoresAll() throws Exception {
        CategoryRule rule = rule(user, food, false);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        List<Transaction> batch = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            Transaction txn = new Transaction();
            txn.setUser(user);
            txn.setAccount(account);
            txn.setAmount(BigDecimal.valueOf(10));
            txn.setDate(LocalDate.parse("2026-10-01"));
            txn.setSource(TransactionSource.manual);
            txn.setType(TransactionType.DEBIT);
            txn.setReviewType(ReviewType.NEEDS_REVIEW);
            txn.setReviewReasons(new HashSet<>(Set.of(ReviewReason.CATEGORY_UNVERIFIED)));
            txn.setAppliedRule(rule);
            batch.add(txn);
        }
        transactionRepository.saveAll(batch).forEach(t -> transactionIds.add(t.getId()));

        var res = approve(a.getId());
        assertEquals(1001, res.get("verifiedRules").get(0).get("clearedTransactions").size());
        var undone = undo(list(res.get("approvedTransactions")), ruleEntry(res.get("verifiedRules").get(0)));

        assertEquals(1002, undone.get("restoredTransactionIds").size());
        assertEquals(0, undone.get("skippedTransactionIds").size());
        assertEquals(Integer.valueOf(1002), jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE applied_rule_id = ? AND review_type = 'NEEDS_REVIEW'",
                Integer.class, rule.getId().toString()));
    }
}
