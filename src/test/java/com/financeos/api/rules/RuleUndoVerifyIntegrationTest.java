package com.financeos.api.rules;

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
class RuleUndoVerifyIntegrationTest {

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
        String email = "undo-verify-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "rulesPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        String otherEmail = "undo-verify-other-" + UUID.randomUUID() + "@example.test";
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

    private ResultActions batchReview(UUID... ids) throws Exception {
        return mockMvc.perform(post("/api/v1/transactions/batch-review")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "transactionIds", Arrays.asList(ids),
                        "reviewType", "MANUALLY_REVIEWED",
                        "reviewReasons", List.of("CATEGORY_UNVERIFIED")))));
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
    void approve_reportsVerifiedRuleAndOtherClearedRows_notTheRequestsOwn() throws Exception {
        CategoryRule rule = rule(user, food, false);
        Transaction approved = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction other1 = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction other2 = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);

        batchReview(approved.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeededIds[0]").value(approved.getId().toString()))
                .andExpect(jsonPath("$.verifiedRules.length()").value(1))
                .andExpect(jsonPath("$.verifiedRules[0].ruleId").value(rule.getId().toString()))
                .andExpect(jsonPath("$.verifiedRules[0].clearedTransactionIds.length()").value(2))
                .andExpect(jsonPath("$.verifiedRules[0].clearedTransactionIds",
                        org.hamcrest.Matchers.containsInAnyOrder(other1.getId().toString(), other2.getId().toString())));

        assertTrue(isVerified(rule));
    }

    @Test
    void approve_rowOfTheSameRequestSkippedByTheVerify_isReportedForUnverify() throws Exception {
        // Approving a verifies the rule, which clears b's reason before b is reached, so b is skipped.
        // b is not among the approved rows the client restores, so it must be in the cleared list.
        CategoryRule rule = rule(user, food, false);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction b = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);

        batchReview(a.getId(), b.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeededIds", org.hamcrest.Matchers.contains(a.getId().toString())))
                .andExpect(jsonPath("$.skippedIds", org.hamcrest.Matchers.contains(b.getId().toString())))
                .andExpect(jsonPath("$.verifiedRules.length()").value(1))
                .andExpect(jsonPath("$.verifiedRules[0].clearedTransactionIds",
                        org.hamcrest.Matchers.contains(b.getId().toString())));
    }

    @Test
    void approve_rowsThatBothSucceed_areNotReportedForUnverify() throws Exception {
        // b carries a second reason, so it still succeeds after the verify cleared CATEGORY_UNVERIFIED.
        CategoryRule rule = rule(user, food, false);
        Transaction a = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction b = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED, ReviewReason.UNRECONCILED);

        mockMvc.perform(post("/api/v1/transactions/batch-review")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "transactionIds", List.of(a.getId(), b.getId()),
                                "reviewType", "MANUALLY_REVIEWED",
                                "reviewReasons", List.of("CATEGORY_UNVERIFIED", "UNRECONCILED")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeededIds.length()").value(2))
                .andExpect(jsonPath("$.verifiedRules[0].clearedTransactionIds.length()").value(0));
    }

    @Test
    void approve_noVerifiedRules_whenRuleAlreadyVerified() throws Exception {
        CategoryRule rule = rule(user, food, true);
        Transaction approved = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);

        batchReview(approved.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedRules.length()").value(0));
    }

    @Test
    void approve_noVerifiedRules_whenNoRule() throws Exception {
        Transaction approved = linkedTransaction(null, ReviewReason.CATEGORY_UNVERIFIED);

        batchReview(approved.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedRules.length()").value(0));
    }

    @Test
    void unverify_unverifiesRuleAndRestoresReasonOnRowsStillOnTheRule() throws Exception {
        CategoryRule rule = rule(user, food, true);
        CategoryRule otherRule = rule(user, food, true);
        Transaction onRule = linkedTransaction(rule);
        Transaction movedOff = linkedTransaction(otherRule);

        unverify(rule, List.of(onRule.getId(), movedOff.getId(), UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredTransactionIds.length()").value(1))
                .andExpect(jsonPath("$.restoredTransactionIds[0]").value(onRule.getId().toString()));

        assertFalse(isVerified(rule));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(onRule));
        assertEquals(Set.of(), reasons(movedOff));
    }

    @Test
    void unverify_skipsAnotherUsersTransactions() throws Exception {
        CategoryRule rule = rule(user, food, true);
        Account otherAccount = new Account();
        otherAccount.setName("Other");
        otherAccount.setUser(otherUser);
        otherAccount.setType(AccountType.bank_account);
        otherAccount = accountRepository.save(otherAccount);
        Transaction theirs = new Transaction();
        theirs.setUser(otherUser);
        theirs.setAccount(otherAccount);
        theirs.setAmount(BigDecimal.TEN);
        theirs.setDate(LocalDate.parse("2026-10-01"));
        theirs.setSource(TransactionSource.manual);
        theirs.setType(TransactionType.DEBIT);
        theirs.setReviewType(ReviewType.NA);
        theirs.setAppliedRule(rule);
        theirs.setCategories(new HashSet<>(Set.of(otherFood)));
        theirs = transactionRepository.save(theirs);
        try {
            unverify(rule, List.of(theirs.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.restoredTransactionIds.length()").value(0));
            assertEquals(Set.of(), reasons(theirs));
        } finally {
            transactionRepository.deleteById(theirs.getId());
            accountRepository.deleteById(otherAccount.getId());
        }
    }

    @Test
    void unverify_anotherUsersRule_isRejectedAndLeavesItVerified() throws Exception {
        CategoryRule theirs = rule(otherUser, otherFood, true);

        unverify(theirs, List.of(UUID.randomUUID())).andExpect(status().isBadRequest());

        assertTrue(isVerified(theirs));
    }

    @Test
    void unverify_unknownRule_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/rules/{id}/unverify", UUID.randomUUID())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionIds\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unverify_emptyList_stillUnverifiesTheRule() throws Exception {
        CategoryRule rule = rule(user, food, true);

        unverify(rule, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredTransactionIds.length()").value(0));

        assertFalse(isVerified(rule));
    }

    @Test
    void unverify_moreThan500Ids_returns400() throws Exception {
        CategoryRule rule = rule(user, food, true);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            ids.add(UUID.randomUUID());
        }

        unverify(rule, ids).andExpect(status().isBadRequest());

        assertTrue(isVerified(rule));
    }

    @Test
    void unverify_missingList_returns400() throws Exception {
        CategoryRule rule = rule(user, food, true);

        unverify(rule, null).andExpect(status().isBadRequest());
    }
}
