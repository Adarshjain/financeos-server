package com.financeos.domain.categorization;

import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.support.TestCategories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class CategorizationServiceTest {

    private CategoryRuleRepository categoryRuleRepository;
    private TransactionRepository transactionRepository;
    private CategorizationService categorizationService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    public void setUp() {
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        categorizationService = new CategorizationService(categoryRuleRepository, transactionRepository,
                TestCategories.writer(categoryRuleRepository));

        userId = UUID.randomUUID();
        testUser = new User();
        testUser.setId(userId);
    }

    private CategoryRule ruleOf(String pattern, MatchType matchType) {
        CategoryRule rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setUser(testUser);
        rule.setMerchantKey(pattern);
        rule.setMatchType(matchType);
        rule.setCategoryKey("food");
        rule.setUpdatedAt(Instant.now());
        return rule;
    }

    private Transaction txn(String sourcedDescription) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUser(testUser);
        t.setType(TransactionType.DEBIT);
        t.setSourcedDescription(sourcedDescription);
        return t;
    }

    @Test
    public void testMoreLiteralMatchTypeWins() {
        // EXACT beats STARTS_WITH beats CONTAINS beats REGEX regardless of pattern length.
        CategoryRule regexRule = ruleOf("SWIGGY INSTAMART.*BLR", MatchType.REGEX);
        CategoryRule containsRule = ruleOf("SWIGGY INSTAMART", MatchType.CONTAINS);
        CategoryRule startsRule = ruleOf("UPI SWIGGY", MatchType.STARTS_WITH);
        CategoryRule exactRule = ruleOf("UPI SWIGGY INSTAMART BLR", MatchType.EXACT);

        when(categoryRuleRepository.findByUserId(userId))
                .thenReturn(List.of(regexRule, containsRule, startsRule, exactRule));
        assertEquals(MatchType.EXACT, categorizationService
                .findBestMatchingRule(userId, "UPI SWIGGY INSTAMART BLR").orElseThrow().getMatchType());

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(regexRule, containsRule, startsRule));
        assertEquals(MatchType.STARTS_WITH, categorizationService
                .findBestMatchingRule(userId, "UPI SWIGGY INSTAMART BLR").orElseThrow().getMatchType());

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(regexRule, containsRule));
        assertEquals(MatchType.CONTAINS, categorizationService
                .findBestMatchingRule(userId, "UPI SWIGGY INSTAMART BLR").orElseThrow().getMatchType());
    }

    @Test
    public void testLongerPatternThenNewestWinsWithinAType() {
        CategoryRule shortRule = ruleOf("SWIGGY", MatchType.CONTAINS);
        shortRule.setUpdatedAt(Instant.now());
        CategoryRule longRule = ruleOf("SWIGGY INSTAMART", MatchType.CONTAINS);
        longRule.setUpdatedAt(Instant.now().minusSeconds(500));
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(shortRule, longRule));

        assertEquals("SWIGGY INSTAMART",
                categorizationService.findBestMatchingRule(userId, "UPI SWIGGY INSTAMART BLR").orElseThrow().getMerchantKey());

        CategoryRule older = ruleOf("SWIGGY", MatchType.CONTAINS);
        older.setUpdatedAt(Instant.now().minusSeconds(100));
        CategoryRule newer = ruleOf("SWIGGY", MatchType.CONTAINS);
        newer.setUpdatedAt(Instant.now());
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(older, newer));

        assertSame(newer, categorizationService.findBestMatchingRule(userId, "UPI SWIGGY BLR").orElseThrow());
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(newer, older));
        assertSame(newer, categorizationService.findBestMatchingRule(userId, "UPI SWIGGY BLR").orElseThrow());
    }

    @Test
    public void testContainsMatchesRawText() {
        CategoryRule rule = ruleOf("UPI-AUTOPAY/042", MatchType.CONTAINS);
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));

        assertTrue(categorizationService.findBestMatchingRule(userId, "upi-autopay/042/netflix").isPresent());
        assertFalse(categorizationService.findBestMatchingRule(userId, "AUTOPAY 042 NETFLIX").isPresent());
    }

    @Test
    public void testStartsWithAndRegexMatching() {
        CategoryRule startsWith = ruleOf("ACH/", MatchType.STARTS_WITH);
        CategoryRule regex = ruleOf("NEFT.*(HDFC|ICICI)", MatchType.REGEX);
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(startsWith, regex));

        assertEquals(MatchType.STARTS_WITH,
                categorizationService.findBestMatchingRule(userId, "ACH/SALARY CREDIT").orElseThrow().getMatchType());
        assertEquals(MatchType.REGEX,
                categorizationService.findBestMatchingRule(userId, "NEFT TRANSFER TO ICICI BANK").orElseThrow().getMatchType());
        assertFalse(categorizationService.findBestMatchingRule(userId, "POS ACH/ SOMETHING").isPresent());
        assertFalse(categorizationService.findBestMatchingRule(userId, "IMPS TRANSFER TO SBI").isPresent());
    }

    @Test
    public void testSuggestFromRulesReturnsTheMatchingRulesCategory() {
        CategoryRule rule = ruleOf("SWIGGY", MatchType.CONTAINS);
        rule.setSubcategoryKey("food.delivery");
        rule.setMcc("5814");
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));

        CategorizationService.RuleSuggestion s = categorizationService.suggestFromRules(userId, "UPI SWIGGY").orElseThrow();
        assertEquals("food", s.categoryKey());
        assertEquals("food.delivery", s.subcategoryKey());
        assertEquals(rule.getId(), s.ruleId());
        assertEquals("5814", s.mcc());

        assertTrue(categorizationService.suggestFromRules(userId, "AMAZON").isEmpty());
        assertTrue(categorizationService.suggestFromRules(userId, "  ").isEmpty());
        assertTrue(categorizationService.suggestFromRules(userId, null).isEmpty());
    }

    @Test
    public void testDeleteRuleClearsRuleSetRowsAndUnlinksTheRest() {
        CategoryRule rule = ruleOf("SWIGGY", MatchType.CONTAINS);
        Transaction byRule = txn("SWIGGY A");
        byRule.setCategoryKey("food");
        byRule.setCategorySource(CategorySource.RULE);
        byRule.setAppliedRule(rule);
        Transaction byLink = txn("SWIGGY B");
        byLink.setCategoryKey("food");
        byLink.setCategorySource(CategorySource.LINK);
        byLink.setAppliedRule(rule);
        when(transactionRepository.findByAppliedRuleId(rule.getId())).thenReturn(List.of(byRule, byLink));

        categorizationService.deleteRule(rule);

        assertNull(byRule.getCategoryKey());
        assertNull(byRule.getCategorySource());
        assertNull(byRule.getAppliedRule());
        assertEquals("food", byLink.getCategoryKey(), "a category the rule did not set stays");
        assertNull(byLink.getAppliedRule());
        verify(categoryRuleRepository).delete(rule);
    }

    @Test
    public void testRecategoriseNeverClearsAUserCategory() {
        Transaction user = txn("SWIGGY A");
        user.setCategoryKey("shopping");
        user.setCategorySource(CategorySource.USER);
        Transaction auto = txn("SWIGGY B");
        auto.setCategoryKey("food");
        auto.setCategorySource(CategorySource.AUTO);

        categorizationService.recategorise(List.of(user, auto));

        assertEquals("shopping", user.getCategoryKey());
        assertNull(auto.getCategoryKey());
        verify(transactionRepository).saveAll(List.of(auto));
    }

    @Test
    public void testEditedRuleCategoryReachesOnlyRowsItStillGoverns() {
        CategoryRule rule = ruleOf("SWIGGY", MatchType.CONTAINS);
        rule.setCategoryKey("groceries");
        Transaction governed = txn("SWIGGY A");
        governed.setCategoryKey("food");
        governed.setCategorySource(CategorySource.RULE);
        governed.setAppliedRule(rule);
        Transaction drifted = txn("AMAZON PAY");
        drifted.setCategoryKey("food");
        drifted.setCategorySource(CategorySource.RULE);
        drifted.setAppliedRule(rule);
        when(transactionRepository.findByAppliedRuleId(rule.getId())).thenReturn(List.of(governed, drifted));

        categorizationService.saveEditedRule(rule, true, true);

        assertEquals("groceries", governed.getCategoryKey());
        assertEquals(CategorySource.RULE, governed.getCategorySource());
        assertSame(rule, governed.getAppliedRule());
        assertNull(drifted.getCategoryKey(), "no longer matches the pattern, so loses the rule's category");
        assertNull(drifted.getAppliedRule());
        verify(categoryRuleRepository).save(rule);
    }
}
