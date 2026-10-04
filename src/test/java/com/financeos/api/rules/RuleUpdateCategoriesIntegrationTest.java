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
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /rules/{id} with new categoryIds re-applies them to the rule's linked transactions. The
 * controller loads the categories in one persistence context and the service updates transactions
 * in another (open-in-view is off), so this goes through the real endpoint. The prod unique
 * constraint is added to the H2 schema so a delete-and-re-insert of a kept category fails exactly
 * as it did in prod (409, ORA-00001 UC_TRANSACTION_CATEGORY, request 6f8e1b6753f840659b60).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuleUpdateCategoriesIntegrationTest {

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
    private Account account;
    private Category food;
    private Category travel;
    private Category shopping;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.execute("ALTER TABLE transaction_categories ADD CONSTRAINT IF NOT EXISTS "
                + "uc_transaction_category UNIQUE (transaction_id, category_id)");

        String email = "rules-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "rulesPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        account = new Account();
        account.setName("Savings");
        account.setUser(user);
        account.setType(AccountType.bank_account);
        account = accountRepository.save(account);

        food = categoryRepository.save(new Category("Food", user));
        travel = categoryRepository.save(new Category("Travel", user));
        shopping = categoryRepository.save(new Category("Shopping", user));
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

    private CategoryRule rule(Category... categories) {
        CategoryRule rule = new CategoryRule();
        rule.setUser(user);
        rule.setMerchantKey("swiggy-" + UUID.randomUUID());
        rule.setMatchType(MatchType.CONTAINS);
        rule.setSource("USER");
        rule.setVerified(true);
        rule.setCategories(new HashSet<>(Set.of(categories)));
        return categoryRuleRepository.save(rule);
    }

    private Transaction linkedTransaction(CategoryRule rule, ReviewType reviewType, Category... categories) {
        Transaction txn = new Transaction();
        txn.setUser(user);
        txn.setAccount(account);
        txn.setAmount(BigDecimal.valueOf(250));
        txn.setDate(LocalDate.parse("2026-10-01"));
        txn.setSource(TransactionSource.manual);
        txn.setType(TransactionType.DEBIT);
        txn.setReviewType(reviewType);
        txn.setAppliedRule(rule);
        txn.setCategories(new HashSet<>(Set.of(categories)));
        return transactionRepository.save(txn);
    }

    private void updateRuleCategories(CategoryRule rule, Category... categories) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("categoryIds", java.util.Arrays.stream(categories).map(Category::getId).toList());
        mockMvc.perform(put("/api/v1/rules/{id}", rule.getId())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    /** category id → transaction_categories row id, straight from the table. */
    private Map<UUID, Long> categoryRows(Transaction txn) {
        return jdbcTemplate.queryForList(
                        "SELECT id, category_id FROM transaction_categories WHERE transaction_id = ?",
                        txn.getId().toString())
                .stream()
                .collect(Collectors.toMap(
                        row -> UUID.fromString(row.get("category_id").toString()),
                        row -> ((Number) row.get("id")).longValue()));
    }

    private Set<UUID> ruleCategoryIds(CategoryRule rule) {
        return jdbcTemplate.queryForList(
                        "SELECT category_id FROM category_rule_categories WHERE rule_id = ?",
                        String.class, rule.getId().toString())
                .stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    @Test
    void removingOneCategory_keepsTheOtherOnLinkedTransactions() throws Exception {
        CategoryRule rule = rule(food, travel);
        Transaction txn = linkedTransaction(rule, ReviewType.AUTO_REVIEWED, food, travel);
        Long keptRowId = categoryRows(txn).get(food.getId());

        updateRuleCategories(rule, food);

        assertEquals(Set.of(food.getId()), ruleCategoryIds(rule));
        Map<UUID, Long> rows = categoryRows(txn);
        assertEquals(Set.of(food.getId()), rows.keySet());
        assertEquals(keptRowId, rows.get(food.getId()), "kept category row must not be deleted and re-inserted");
    }

    @Test
    void addingACategory_keepsExistingAndAddsNewOnLinkedTransactions() throws Exception {
        CategoryRule rule = rule(food);
        Transaction txn = linkedTransaction(rule, ReviewType.AUTO_REVIEWED, food);
        Long keptRowId = categoryRows(txn).get(food.getId());

        updateRuleCategories(rule, food, travel);

        assertEquals(Set.of(food.getId(), travel.getId()), ruleCategoryIds(rule));
        Map<UUID, Long> rows = categoryRows(txn);
        assertEquals(Set.of(food.getId(), travel.getId()), rows.keySet());
        assertEquals(keptRowId, rows.get(food.getId()));
    }

    @Test
    void replacingAllCategories_swapsThemOnLinkedTransactions() throws Exception {
        CategoryRule rule = rule(food, travel);
        Transaction txn = linkedTransaction(rule, ReviewType.AUTO_REVIEWED, food, travel);

        updateRuleCategories(rule, shopping);

        assertEquals(Set.of(shopping.getId()), ruleCategoryIds(rule));
        assertEquals(Set.of(shopping.getId()), categoryRows(txn).keySet());
    }

    @Test
    void manuallyReviewedLinkedTransaction_isLeftUntouched() throws Exception {
        CategoryRule rule = rule(food, travel);
        Transaction autoTxn = linkedTransaction(rule, ReviewType.AUTO_REVIEWED, food, travel);
        Transaction manualTxn = linkedTransaction(rule, ReviewType.MANUALLY_REVIEWED, food, travel);
        Map<UUID, Long> manualRowsBefore = categoryRows(manualTxn);

        updateRuleCategories(rule, food);

        assertEquals(Set.of(food.getId()), categoryRows(autoTxn).keySet());
        assertEquals(manualRowsBefore, categoryRows(manualTxn));
    }

    @Test
    void ruleWithSeveralLinkedTransactions_updatesEveryOne() throws Exception {
        CategoryRule rule = rule(food, travel);
        List<Transaction> txns = List.of(
                linkedTransaction(rule, ReviewType.AUTO_REVIEWED, food, travel),
                linkedTransaction(rule, ReviewType.NEEDS_REVIEW, food, travel),
                linkedTransaction(rule, ReviewType.AUTO_REVIEWED, food, travel));

        updateRuleCategories(rule, travel);

        for (Transaction txn : txns) {
            assertEquals(Set.of(travel.getId()), categoryRows(txn).keySet());
        }
    }
}
