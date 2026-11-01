package com.financeos.api.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.MatchType;
import com.financeos.domain.transaction.CategorySource;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PUT /rules/{id} with a new category re-applies it to the transactions the rule set (linked by
 * applied_rule_id), and DELETE /rules/{id} takes back what it set. A user's own category is never
 * touched. Goes through the real endpoints because the controller and the service run in different
 * persistence contexts (open-in-view is off).
 */
@SpringBootTest
@AutoConfigureMockMvc
class RuleUpdateCategoriesIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRuleRepository categoryRuleRepository;
    @Autowired private TransactionRepository transactionRepository;

    private Cookie session;
    private User user;
    private Account account;
    private final List<UUID> ruleIds = new ArrayList<>();
    private final List<UUID> transactionIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        String email = "rules-" + UUID.randomUUID() + "@example.test";
        session = authenticate(email, "rulesPass123!");
        user = userRepository.findByEmail(email).orElseThrow();

        account = new Account();
        account.setName("Savings");
        account.setUser(user);
        account.setType(AccountType.bank_account);
        account = accountRepository.save(account);
    }

    /**
     * Every @SpringBootTest class shares one in-memory H2 database and a cached context does not
     * recreate the schema, so rows left here break later tests that wipe whole tables (users FK).
     * Deleted by id so each entity is reloaded fresh rather than merging the stale detached copies.
     */
    @AfterEach
    void tearDown() {
        transactionRepository.deleteAllById(transactionIds);
        categoryRuleRepository.deleteAllById(ruleIds);
        accountRepository.deleteById(account.getId());
        userRepository.deleteById(user.getId());
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

    private CategoryRule rule(String pattern, MatchType matchType, String categoryKey) {
        CategoryRule rule = new CategoryRule();
        rule.setUser(user);
        rule.setMerchantKey(pattern);
        rule.setMatchType(matchType);
        rule.setCategoryKey(categoryKey);
        CategoryRule saved = categoryRuleRepository.save(rule);
        ruleIds.add(saved.getId());
        return saved;
    }

    /** A debit the rule set: category = the rule's, source RULE, linked to the rule. */
    private Transaction ruleSet(CategoryRule rule, String sourcedDescription) {
        Transaction txn = transaction(sourcedDescription);
        txn.setAppliedRule(rule);
        txn.setCategoryKey(rule.getCategoryKey());
        txn.setCategorySource(CategorySource.RULE);
        return transactionRepository.save(txn);
    }

    private Transaction transaction(String sourcedDescription) {
        Transaction txn = new Transaction();
        txn.setUser(user);
        txn.setAccount(account);
        txn.setAmount(BigDecimal.valueOf(250));
        txn.setDate(LocalDate.parse("2026-10-01"));
        txn.setSource(TransactionSource.manual);
        txn.setType(TransactionType.DEBIT);
        txn.setSourcedDescription(sourcedDescription);
        Transaction saved = transactionRepository.save(txn);
        transactionIds.add(saved.getId());
        return saved;
    }

    private void updateRule(CategoryRule rule, Map<String, Object> body) throws Exception {
        mockMvc.perform(put("/api/v1/rules/{id}", rule.getId())
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private Map<String, Object> row(Transaction txn) {
        return jdbcTemplate.queryForList(
                "SELECT category_key, subcategory_key, category_source, applied_rule_id FROM transactions WHERE id = ?",
                txn.getId().toString()).get(0);
    }

    private String categoryKey(Transaction txn) {
        return (String) row(txn).get("category_key");
    }

    private UUID appliedRuleId(Transaction txn) {
        Object id = row(txn).get("applied_rule_id");
        return id == null ? null : UUID.fromString(id.toString());
    }

    @Test
    void categoryChange_reachesEveryRowTheRuleSet() throws Exception {
        CategoryRule rule = rule("swiggy", MatchType.CONTAINS, "food");
        List<Transaction> txns = List.of(
                ruleSet(rule, "UPI SWIGGY A"), ruleSet(rule, "UPI SWIGGY B"), ruleSet(rule, "UPI SWIGGY C"));

        Map<String, Object> body = new HashMap<>();
        body.put("categoryKey", "groceries");
        body.put("subcategoryKey", "groceries.supermarket");
        updateRule(rule, body);

        for (Transaction txn : txns) {
            assertEquals("groceries", categoryKey(txn));
            assertEquals("groceries.supermarket", row(txn).get("subcategory_key"));
            assertEquals("RULE", row(txn).get("category_source"));
            assertEquals(rule.getId(), appliedRuleId(txn));
        }
        CategoryRule saved = categoryRuleRepository.findById(rule.getId()).orElseThrow();
        assertEquals("groceries", saved.getCategoryKey());
        assertEquals("groceries.supermarket", saved.getSubcategoryKey());
    }

    @Test
    void categoryChange_leavesAUserSetRowAlone() throws Exception {
        CategoryRule rule = rule("swiggy", MatchType.CONTAINS, "food");
        Transaction byRule = ruleSet(rule, "UPI SWIGGY A");
        Transaction byUser = ruleSet(rule, "UPI SWIGGY B");
        byUser.setCategoryKey("shopping");
        byUser.setCategorySource(CategorySource.USER);
        transactionRepository.save(byUser);

        updateRule(rule, Map.of("categoryKey", "groceries"));

        assertEquals("groceries", categoryKey(byRule));
        assertEquals("shopping", categoryKey(byUser));
        assertEquals("USER", row(byUser).get("category_source"));
    }

    @Test
    void editWithoutACategory_leavesRowsAsTheyAre() throws Exception {
        CategoryRule rule = rule("swiggy", MatchType.CONTAINS, "food");
        Transaction txn = ruleSet(rule, "UPI SWIGGY A");

        updateRule(rule, Map.of("displayName", "Swiggy orders"));

        assertEquals("food", categoryKey(txn));
        assertEquals("Swiggy orders", categoryRuleRepository.findById(rule.getId()).orElseThrow().getDisplayName());
    }

    @Test
    void patternChange_clearsRowsItNoLongerMatches() throws Exception {
        CategoryRule rule = rule("zorbo", MatchType.CONTAINS, "food");
        Transaction stillMatches = ruleSet(rule, "UPI ZORBO ALPHA");
        Transaction noLongerMatches = ruleSet(rule, "UPI ZORBO BETA");

        updateRule(rule, Map.of("merchantKey", "alpha", "matchType", "CONTAINS"));

        assertEquals(rule.getId(), appliedRuleId(stillMatches));
        assertEquals("food", categoryKey(stillMatches));
        assertNull(appliedRuleId(noLongerMatches));
        assertNull(categoryKey(noLongerMatches));
        assertNull(row(noLongerMatches).get("category_source"));
    }

    @Test
    void patternAndCategoryChange_newCategoryReachesOnlyRowsStillMatching() throws Exception {
        CategoryRule rule = rule("zorbo", MatchType.CONTAINS, "food");
        Transaction stillMatches = ruleSet(rule, "UPI ZORBO ALPHA");
        Transaction noLongerMatches = ruleSet(rule, "UPI ZORBO BETA");

        updateRule(rule, Map.of("merchantKey", "alpha", "matchType", "CONTAINS", "categoryKey", "shopping"));

        assertEquals("shopping", categoryKey(stillMatches));
        assertNull(appliedRuleId(noLongerMatches));
        assertNull(categoryKey(noLongerMatches));
    }

    @Test
    void matchTypeChange_clearsRowsItNoLongerMatches() throws Exception {
        CategoryRule rule = rule("zorbo", MatchType.CONTAINS, "food");
        Transaction startsWith = ruleSet(rule, "ZORBO ORDER 42");
        Transaction contains = ruleSet(rule, "UPI ZORBO ORDER 43");

        updateRule(rule, Map.of("merchantKey", "zorbo", "matchType", "STARTS_WITH"));

        assertEquals(rule.getId(), appliedRuleId(startsWith));
        assertNull(appliedRuleId(contains));
        assertNull(categoryKey(contains));
    }

    /** The edit dialog always resends the pattern; an unchanged one must not unlink anything. */
    @Test
    void unchangedPatternResent_keepsEveryLinkAndAppliesTheCategory() throws Exception {
        CategoryRule rule = rule("swiggy", MatchType.CONTAINS, "food");
        Transaction matching = ruleSet(rule, "UPI SWIGGY ORDER");
        Transaction notMatching = ruleSet(rule, "ZOMATO ORDER");

        updateRule(rule, Map.of("merchantKey", "swiggy", "matchType", "CONTAINS", "categoryKey", "travel"));

        assertEquals(rule.getId(), appliedRuleId(matching));
        assertEquals(rule.getId(), appliedRuleId(notMatching));
        assertEquals("travel", categoryKey(matching));
        assertEquals("travel", categoryKey(notMatching));
    }

    @Test
    void unknownCategoryOrForeignSubcategory_is400AndChangesNothing() throws Exception {
        CategoryRule rule = rule("swiggy", MatchType.CONTAINS, "food");
        Transaction txn = ruleSet(rule, "UPI SWIGGY A");

        for (Map<String, Object> body : List.of(
                Map.<String, Object>of("categoryKey", "nope"),
                Map.<String, Object>of("categoryKey", "food", "subcategoryKey", "travel.flights"))) {
            mockMvc.perform(put("/api/v1/rules/{id}", rule.getId()).cookie(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
        assertEquals("food", categoryKey(txn));
        assertEquals("food", categoryRuleRepository.findById(rule.getId()).orElseThrow().getCategoryKey());
    }

    @Test
    void deletingTheRule_takesBackWhatItSetAndKeepsTheUsersOwn() throws Exception {
        CategoryRule rule = rule("zorbo", MatchType.CONTAINS, "food");
        Transaction byRule = ruleSet(rule, "UPI ZORBO A");
        Transaction byUser = ruleSet(rule, "UPI ZORBO B");
        byUser.setCategoryKey("shopping");
        byUser.setCategorySource(CategorySource.USER);
        transactionRepository.save(byUser);

        mockMvc.perform(delete("/api/v1/rules/{id}", rule.getId()).cookie(session))
                .andExpect(status().isNoContent());

        assertTrue(categoryRuleRepository.findById(rule.getId()).isEmpty());
        assertNull(categoryKey(byRule));
        assertNull(appliedRuleId(byRule));
        assertEquals("shopping", categoryKey(byUser));
        assertNull(appliedRuleId(byUser));
    }

    /** A row the rule gives up falls back to the built-in engine (not to null) when the engine knows it. */
    @Test
    void deletingTheRule_sendsRowsBackThroughTheBuiltInEngine() throws Exception {
        CategoryRule rule = rule("swiggy", MatchType.CONTAINS, "shopping");
        Transaction txn = ruleSet(rule, "UPI SWIGGY A");

        mockMvc.perform(delete("/api/v1/rules/{id}", rule.getId()).cookie(session))
                .andExpect(status().isNoContent());

        assertEquals("food", categoryKey(txn));
        assertEquals("AUTO", row(txn).get("category_source"));
        assertNull(appliedRuleId(txn));
    }
}
