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
 * POST /rules/verify approves several rules in one call: each becomes verified and its linked
 * transactions lose CATEGORY_UNVERIFIED, exactly as POST /rules/{id}/verify does for one rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuleBulkVerifyIntegrationTest {

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
        String email = "bulk-verify-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "rulesPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        String otherEmail = "bulk-verify-other-" + UUID.randomUUID() + "@example.test";
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

    private ResultActions bulkVerify(Object ruleIdsBody) throws Exception {
        return mockMvc.perform(post("/api/v1/rules/verify")
                .cookie(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Collections.singletonMap("ruleIds", ruleIdsBody))));
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

    private String reviewType(Transaction txn) {
        return jdbcTemplate.queryForObject(
                "SELECT review_type FROM transactions WHERE id = ?", String.class, txn.getId().toString());
    }

    @Test
    void verifiesEverySelectedRuleAndReturnsTheCount() throws Exception {
        CategoryRule swiggy = rule(user, food, false);
        CategoryRule zomato = rule(user, food, false);

        bulkVerify(List.of(swiggy.getId(), zomato.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedCount").value(2));

        assertTrue(isVerified(swiggy));
        assertTrue(isVerified(zomato));
    }

    @Test
    void clearsCategoryUnverifiedFromTransactionsLinkedToEachRule() throws Exception {
        CategoryRule swiggy = rule(user, food, false);
        CategoryRule zomato = rule(user, food, false);
        Transaction onlyReason = linkedTransaction(swiggy, ReviewReason.CATEGORY_UNVERIFIED);
        Transaction otherReasonToo = linkedTransaction(zomato,
                ReviewReason.CATEGORY_UNVERIFIED, ReviewReason.UNRECONCILED);

        bulkVerify(List.of(swiggy.getId(), zomato.getId())).andExpect(status().isOk());

        assertEquals(Set.of(), reasons(onlyReason));
        assertEquals(ReviewType.AUTO_REVIEWED.name(), reviewType(onlyReason));
        assertEquals(Set.of(ReviewReason.UNRECONCILED.name()), reasons(otherReasonToo));
        assertEquals(ReviewType.NEEDS_REVIEW.name(), reviewType(otherReasonToo));
    }

    @Test
    void rulesNotInTheRequestAreLeftUnverified() throws Exception {
        CategoryRule selected = rule(user, food, false);
        CategoryRule notSelected = rule(user, food, false);
        Transaction notSelectedTxn = linkedTransaction(notSelected, ReviewReason.CATEGORY_UNVERIFIED);

        bulkVerify(List.of(selected.getId())).andExpect(status().isOk());

        assertFalse(isVerified(notSelected));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(notSelectedTxn));
        assertEquals(ReviewType.NEEDS_REVIEW.name(), reviewType(notSelectedTxn));
    }

    @Test
    void alreadyVerifiedRulesAreAcceptedButNotCounted() throws Exception {
        CategoryRule unverified = rule(user, food, false);
        CategoryRule verified = rule(user, food, true);

        bulkVerify(List.of(unverified.getId(), verified.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedCount").value(1));

        assertTrue(isVerified(unverified));
        assertTrue(isVerified(verified));
    }

    @Test
    void duplicateIdsAreCountedOnce() throws Exception {
        CategoryRule rule = rule(user, food, false);

        bulkVerify(List.of(rule.getId(), rule.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedCount").value(1));

        assertTrue(isVerified(rule));
    }

    @Test
    void anotherUsersRule_returns404AndVerifiesNothing() throws Exception {
        CategoryRule mine = rule(user, food, false);
        CategoryRule theirs = rule(otherUser, otherFood, false);

        bulkVerify(List.of(mine.getId(), theirs.getId())).andExpect(status().isNotFound());

        assertFalse(isVerified(mine));
        assertFalse(isVerified(theirs));
    }

    @Test
    void unknownRuleId_returns404AndVerifiesNothing() throws Exception {
        CategoryRule mine = rule(user, food, false);
        Transaction txn = linkedTransaction(mine, ReviewReason.CATEGORY_UNVERIFIED);

        bulkVerify(List.of(mine.getId(), UUID.randomUUID())).andExpect(status().isNotFound());

        assertFalse(isVerified(mine));
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED.name()), reasons(txn));
    }

    @Test
    void emptyList_returns400() throws Exception {
        bulkVerify(List.of()).andExpect(status().isBadRequest());
    }

    @Test
    void missingList_returns400() throws Exception {
        bulkVerify(null).andExpect(status().isBadRequest());
    }

    @Test
    void nullId_returns400() throws Exception {
        CategoryRule rule = rule(user, food, false);

        bulkVerify(Arrays.asList(rule.getId(), null)).andExpect(status().isBadRequest());

        assertFalse(isVerified(rule));
    }

    @Test
    void moreThan500Ids_returns400() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            ids.add(UUID.randomUUID());
        }

        bulkVerify(ids).andExpect(status().isBadRequest());
    }

    /** The single-rule endpoint now delegates to the bulk service method; its behaviour is unchanged. */
    @Test
    void singleVerifyEndpoint_stillVerifiesAndClearsLinkedTransactions() throws Exception {
        CategoryRule rule = rule(user, food, false);
        Transaction txn = linkedTransaction(rule, ReviewReason.CATEGORY_UNVERIFIED);

        mockMvc.perform(post("/api/v1/rules/{id}/verify", rule.getId()).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        assertTrue(isVerified(rule));
        assertEquals(Set.of(), reasons(txn));
        assertEquals(ReviewType.AUTO_REVIEWED.name(), reviewType(txn));
    }
}
