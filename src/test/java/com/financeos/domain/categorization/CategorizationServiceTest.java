package com.financeos.domain.categorization;

import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.transaction.*;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CategorizationServiceTest {

    private CategoryRuleRepository categoryRuleRepository;
    private CategoryRepository categoryRepository;
    private TransactionRepository transactionRepository;
    private UserRepository userRepository;
    private TransactionCategorizer transactionCategorizer;
    private ReviewStatusManager reviewStatusManager;
    private CategorizationService categorizationService;

    private User testUser;
    private UUID userId;
    private Category foodCategory;
    private Category shoppingCategory;

    @BeforeEach
    public void setUp() {
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        userRepository = mock(UserRepository.class);
        transactionCategorizer = mock(TransactionCategorizer.class);
        reviewStatusManager = mock(ReviewStatusManager.class);

        categorizationService = new CategorizationService(
                categoryRuleRepository,
                categoryRepository,
                transactionRepository,
                userRepository,
                transactionCategorizer,
                reviewStatusManager,
                null
        );

        userId = UUID.randomUUID();
        testUser = new User();
        testUser.setId(userId);

        foodCategory = new Category("Food & Dining", testUser);
        foodCategory.setId(UUID.randomUUID());
        shoppingCategory = new Category("Shopping", testUser);
        shoppingCategory.setId(UUID.randomUUID());

        when(categoryRepository.findAll()).thenReturn(List.of(foodCategory, shoppingCategory));
        when(categoryRepository.findByUserId(any(UUID.class))).thenReturn(List.of(foodCategory, shoppingCategory));
        when(categoryRuleRepository.save(any(CategoryRule.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.getReferenceById(userId)).thenReturn(testUser);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> {
            Category c = invocation.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            return c;
        });
    }

    @Test
    public void testRuleMatchingPrecedence() {
        CategoryRule ruleShort = new CategoryRule();
        ruleShort.setId(UUID.randomUUID());
        ruleShort.setMerchantKey("SWIGGY");
        ruleShort.setVerified(false);
        ruleShort.setUpdatedAt(Instant.now().minusSeconds(100));

        CategoryRule ruleLong = new CategoryRule();
        ruleLong.setId(UUID.randomUUID());
        ruleLong.setMerchantKey("SWIGGY INSTAMART");
        ruleLong.setVerified(false);
        ruleLong.setUpdatedAt(Instant.now());

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(ruleShort, ruleLong));

        // Longest key wins check: "SWIGGY INSTAMART" is longer than "SWIGGY"
        Optional<CategoryRule> match = categorizationService.findBestMatchingRule(userId, "UPI SWIGGY INSTAMART BANGALORE");
        assertTrue(match.isPresent());
        assertEquals("SWIGGY INSTAMART", match.get().getMerchantKey());

        // Verified tie-break check: same length, verified wins
        CategoryRule ruleShortVerified = new CategoryRule();
        ruleShortVerified.setId(UUID.randomUUID());
        ruleShortVerified.setMerchantKey("SWIGGY");
        ruleShortVerified.setVerified(true);
        ruleShortVerified.setUpdatedAt(Instant.now().minusSeconds(50));

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(ruleShort, ruleShortVerified));
        match = categorizationService.findBestMatchingRule(userId, "UPI SWIGGY BANGALORE");
        assertTrue(match.isPresent());
        assertTrue(match.get().isVerified());

        // Min length guard: key < 3 characters should be ignored
        CategoryRule ruleTooShort = new CategoryRule();
        ruleTooShort.setId(UUID.randomUUID());
        ruleTooShort.setMerchantKey("SW");
        ruleTooShort.setVerified(true);
        ruleTooShort.setUpdatedAt(Instant.now());

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(ruleTooShort));
        match = categorizationService.findBestMatchingRule(userId, "SW BANGALORE");
        assertFalse(match.isPresent());
    }

    @Test
    public void testUserRuleAlwaysBeatsLlmRule() {
        // LLM rule has a longer key and more literal type; USER source must still win.
        CategoryRule llmRule = new CategoryRule();
        llmRule.setId(UUID.randomUUID());
        llmRule.setMerchantKey("SWIGGY INSTAMART");
        llmRule.setMatchType(MatchType.EXACT);
        llmRule.setSource("LLM");
        llmRule.setVerified(true);
        llmRule.setUpdatedAt(Instant.now());

        CategoryRule userRule = new CategoryRule();
        userRule.setId(UUID.randomUUID());
        userRule.setMerchantKey("SWIGGY");
        userRule.setMatchType(MatchType.MERCHANT_KEY);
        userRule.setSource("USER");
        userRule.setVerified(false);
        userRule.setUpdatedAt(Instant.now().minusSeconds(500));

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(llmRule, userRule));

        Optional<CategoryRule> match = categorizationService.findBestMatchingRule(userId, "SWIGGY INSTAMART");
        assertTrue(match.isPresent());
        assertEquals("USER", match.get().getSource());
    }

    @Test
    public void testMoreLiteralMatchTypeWins() {
        // Same source: EXACT beats CONTAINS beats MERCHANT_KEY regardless of key length.
        CategoryRule merchantKeyRule = ruleOf("SWIGGY INSTAMART BANGALORE", MatchType.MERCHANT_KEY);
        CategoryRule containsRule = ruleOf("SWIGGY INSTAMART", MatchType.CONTAINS);
        CategoryRule exactRule = ruleOf("UPI SWIGGY INSTAMART BLR", MatchType.EXACT);

        when(categoryRuleRepository.findByUserId(userId))
                .thenReturn(List.of(merchantKeyRule, containsRule, exactRule));

        Optional<CategoryRule> match = categorizationService.findBestMatchingRule(userId, "UPI SWIGGY INSTAMART BLR");
        assertTrue(match.isPresent());
        assertEquals(MatchType.EXACT, match.get().getMatchType());
    }

    @Test
    public void testContainsMatchesRawTextTheNormalizerWouldEat() {
        // "UPI" is a noise token and "042" is all digits — a MERCHANT_KEY rule can never
        // hold this pattern, but a raw CONTAINS rule matches it.
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

        Optional<CategoryRule> match = categorizationService.findBestMatchingRule(userId, "ACH/SALARY CREDIT");
        assertTrue(match.isPresent());
        assertEquals(MatchType.STARTS_WITH, match.get().getMatchType());

        match = categorizationService.findBestMatchingRule(userId, "NEFT TRANSFER TO ICICI BANK");
        assertTrue(match.isPresent());
        assertEquals(MatchType.REGEX, match.get().getMatchType());

        // startsWith must not fire mid-string; the regex requires NEFT + a matching bank
        assertFalse(categorizationService.findBestMatchingRule(userId, "POS ACH/ SOMETHING").isPresent());
        assertFalse(categorizationService.findBestMatchingRule(userId, "IMPS TRANSFER TO SBI").isPresent());
    }

    @Test
    public void testManualTransactionsAreNeverCategorized() {
        // Manually created transactions have only a user-written description (no
        // sourcedDescription) and are deliberately outside the rule system.
        CategoryRule rule = ruleOf("SWIGGY", MatchType.MERCHANT_KEY);
        rule.setCategories(Set.of(foodCategory));
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setDescription("SWIGGY FOOD DELIVERY");
        txn.setCategories(new HashSet<>());

        categorizationService.batchCategorize(List.of(txn));

        assertTrue(txn.getCategories().isEmpty());
        assertNull(txn.getAppliedRule());
        verify(transactionCategorizer, never()).categorize(any(), any());
        verify(reviewStatusManager, never()).addReason(any(), any());
    }

    private CategoryRule ruleOf(String pattern, MatchType matchType) {
        CategoryRule rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setMerchantKey(pattern);
        rule.setMatchType(matchType);
        rule.setSource("USER");
        rule.setVerified(true);
        rule.setUpdatedAt(Instant.now());
        return rule;
    }

    @Test
    public void testBatchCategorizeVerifiedHit() {
        CategoryRule rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setMerchantKey("SWIGGY");
        rule.setVerified(true);
        rule.setCategories(Set.of(foodCategory));

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));
        when(categoryRuleRepository.findWithCategoriesById(rule.getId())).thenReturn(Optional.of(rule));

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY FOOD DELIVERY");
        txn.setCategories(new HashSet<>());

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        assertTrue(txn.getCategories().stream().anyMatch(tc -> tc.getCategory().equals(foodCategory)));
        assertEquals(rule, txn.getAppliedRule());
        verify(reviewStatusManager, never()).addReason(any(), any());
    }

    @Test
    public void testBatchCategorizeUnverifiedHit() {
        CategoryRule rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setMerchantKey("SWIGGY");
        rule.setVerified(false);
        rule.setCategories(Set.of(foodCategory));

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));
        when(categoryRuleRepository.findWithCategoriesById(rule.getId())).thenReturn(Optional.of(rule));

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY FOOD DELIVERY");
        txn.setCategories(new HashSet<>());

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        assertEquals(rule, txn.getAppliedRule());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testBatchCategorizeRuleMissCreatesRule() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("AMAZON PAY INDIA");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "AMAZON",
                "Amazon",
                List.of("Shopping"),
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        CategoryRule newRule = new CategoryRule();
        newRule.setId(UUID.randomUUID());
        newRule.setMerchantKey("AMAZON");
        newRule.setVerified(false);
        newRule.setCategories(Set.of(shoppingCategory));

        when(categoryRuleRepository.findByUserIdAndMerchantKeyAndMatchType(userId, "AMAZON", MatchType.MERCHANT_KEY)).thenReturn(Optional.empty());
        when(categoryRuleRepository.saveAndFlush(any())).thenReturn(newRule);

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
        assertNotNull(txn.getAppliedRule());
    }

    @Test
    public void testBatchCategorizeLlmFailure() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(transactionCategorizer.categorize(any(), any())).thenReturn(Collections.emptyList()); // Failure / Empty

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("AMAZON PAY INDIA");
        txn.setCategories(new HashSet<>());

        categorizationService.batchCategorize(List.of(txn));

        assertTrue(txn.getCategories().isEmpty());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testBatchCategorizeNoFit() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("XYZ PAYMENTS");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "XYZ",
                "Xyz",
                Collections.emptyList(),
                true // noFit = true
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertTrue(txn.getCategories().isEmpty());
        assertNull(txn.getAppliedRule());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testNovelCategoryCreatedAndAssigned() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "SWIGGY",
                "Swiggy",
                List.of("Charity"),
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        Category assigned = txn.getCategories().iterator().next().getCategory();
        assertEquals("Charity", assigned.getName());
        verify(categoryRepository, times(1)).save(any(Category.class));
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testNovelCategoryNameOverTwoWordsRejected() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "SWIGGY",
                "Swiggy",
                List.of("Online Food Delivery"), // 3-word novel name: over the creation cap
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertTrue(txn.getCategories().isEmpty());
        verify(categoryRepository, never()).save(any(Category.class));
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testExistingMultiWordCategoryStillAccepted() {
        // The word cap only gates creation - "Food & Dining" (3 tokens) exists, so it must resolve.
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "SWIGGY",
                "Swiggy",
                List.of("Food & Dining"),
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        assertEquals(foodCategory, txn.getCategories().iterator().next().getCategory());
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    public void testNoCategoryCreatedWhenMerchantKeyHallucinated() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "AMAZON", // Hallucinated merchant key: item is rejected, so the novel category must not be created
                "Amazon",
                List.of("Shopping Extras"),
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertTrue(txn.getCategories().isEmpty());
        verify(categoryRepository, never()).save(any(Category.class));
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testNoCategoryCreatedWhenAnotherNameInSameItemIsInvalid() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "SWIGGY",
                "Swiggy",
                List.of("Dining Extras", "   "), // one valid novel name + one blank: whole item rejected
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertTrue(txn.getCategories().isEmpty());
        verify(categoryRepository, never()).save(any(Category.class));
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testZeroCategoriesEndToEnd() {
        when(categoryRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "SWIGGY",
                "Swiggy",
                List.of("Food"),
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        Category assigned = txn.getCategories().iterator().next().getCategory();
        assertEquals("Food", assigned.getName());
        verify(categoryRepository, times(1)).save(any(Category.class));
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testCaseInsensitiveCategoryReuse() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "SWIGGY",
                "Swiggy",
                List.of("food & dining"),
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        Category assigned = txn.getCategories().iterator().next().getCategory();
        assertEquals(foodCategory, assigned);
        verify(categoryRepository, never()).save(any(Category.class));
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testBatchLocalCategoryDeduplication() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn1 = new Transaction();
        txn1.setUser(testUser);
        txn1.setSourcedDescription("SWIGGY DELIVERY");
        txn1.setCategories(new HashSet<>());

        Transaction txn2 = new Transaction();
        txn2.setUser(testUser);
        txn2.setSourcedDescription("ZOMATO ORDER");
        txn2.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response1 = new TransactionCategorizer.CategorizeItemResponse(
                0, "SWIGGY", "Swiggy", List.of("Dining Out"), false
        );
        TransactionCategorizer.CategorizeItemResponse response2 = new TransactionCategorizer.CategorizeItemResponse(
                1, "ZOMATO", "Zomato", List.of("dining out"), false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response1, response2));

        categorizationService.batchCategorize(List.of(txn1, txn2));

        assertEquals(1, txn1.getCategories().size());
        assertEquals(1, txn2.getCategories().size());
        Category cat1 = txn1.getCategories().iterator().next().getCategory();
        Category cat2 = txn2.getCategories().iterator().next().getCategory();
        assertEquals("Dining Out", cat1.getName());
        assertEquals(cat1, cat2);
        verify(categoryRepository, times(1)).save(any(Category.class));
    }

    @Test
    public void testSanitizationRejectsInvalidCategoryNames() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txnBlank = new Transaction();
        txnBlank.setUser(testUser);
        txnBlank.setSourcedDescription("SWIGGY DELIVERY");
        txnBlank.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse resBlank = new TransactionCategorizer.CategorizeItemResponse(
                0, "SWIGGY", "Swiggy", List.of("   "), false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(resBlank));

        categorizationService.batchCategorize(List.of(txnBlank));
        assertTrue(txnBlank.getCategories().isEmpty());
        verify(categoryRepository, never()).save(any(Category.class));
        verify(reviewStatusManager, times(1)).addReason(txnBlank, ReviewReason.CATEGORY_UNVERIFIED);

        String longName = "A".repeat(61);
        Transaction txnLong = new Transaction();
        txnLong.setUser(testUser);
        txnLong.setSourcedDescription("SWIGGY DELIVERY");
        txnLong.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse resLong = new TransactionCategorizer.CategorizeItemResponse(
                0, "SWIGGY", "Swiggy", List.of(longName), false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(resLong));

        categorizationService.batchCategorize(List.of(txnLong));
        assertTrue(txnLong.getCategories().isEmpty());
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    public void testHallucinatedMerchantKeyDiscarded() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "AMAZON", // Hallucinated merchant key (AMAZON is not in "SWIGGY DELIVERY")
                "Amazon",
                List.of("Shopping"),
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        // Should fall back to LLM failure (uncategorized + CATEGORY_UNVERIFIED)
        assertTrue(txn.getCategories().isEmpty());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testBatchCategorizeUsesSourcedDescription() {
        // Ingested transactions (file upload / gmail) set only sourcedDescription, never description.
        CategoryRule rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setMerchantKey("SWIGGY");
        rule.setVerified(true);
        rule.setMcc("5812");
        rule.setCategories(Set.of(foodCategory));

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));
        when(categoryRuleRepository.findWithCategoriesById(rule.getId())).thenReturn(Optional.of(rule));

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY FOOD DELIVERY");
        txn.setCategories(new HashSet<>());

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        assertEquals(rule, txn.getAppliedRule());
        assertEquals("5812", txn.getMcc());
    }

    @Test
    public void testBatchCategorizeSourcedDescriptionLlmMiss() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("AMAZON PAY INDIA");
        txn.setCategories(new HashSet<>());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0,
                "AMAZON",
                "Amazon",
                List.of("Shopping"),
                false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        when(categoryRuleRepository.findByUserIdAndMerchantKeyAndMatchType(userId, "AMAZON", MatchType.MERCHANT_KEY)).thenReturn(Optional.empty());

        categorizationService.batchCategorize(List.of(txn));

        // The merchant-key validity check must normalize sourcedDescription, not the null description.
        assertEquals(1, txn.getCategories().size());
        assertNotNull(txn.getAppliedRule());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testBatchCategorizeAppliesMcc() {
        CategoryRule rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setMerchantKey("SWIGGY");
        rule.setVerified(true);
        rule.setMcc("5812");
        rule.setCategories(Set.of(foodCategory));

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));
        when(categoryRuleRepository.findWithCategoriesById(rule.getId())).thenReturn(Optional.of(rule));

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY FOOD DELIVERY");
        txn.setCategories(new HashSet<>());

        categorizationService.batchCategorize(List.of(txn));

        assertEquals("5812", txn.getMcc());
        assertEquals(rule, txn.getAppliedRule());
    }

    @Test
    public void testBatchCategorizeDoesNotOverwriteExistingMcc() {
        CategoryRule rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setMerchantKey("SWIGGY");
        rule.setVerified(true);
        rule.setMcc("5812");
        rule.setCategories(Set.of(foodCategory));

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));
        when(categoryRuleRepository.findWithCategoriesById(rule.getId())).thenReturn(Optional.of(rule));

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setSourcedDescription("SWIGGY FOOD DELIVERY");
        txn.setCategories(new HashSet<>());
        txn.setMcc("5411"); // Pre-existing MCC from card statement

        categorizationService.batchCategorize(List.of(txn));

        assertEquals("5411", txn.getMcc());
        assertEquals(rule, txn.getAppliedRule());
    }

    @Test
    public void testSuggestZeroCategoriesCallsLlmAndCreatesCategory() {
        // T1: zero categories, LLM returns ["Food"] -> LLM was called with empty list; save called once; result has 1 category, fromRule=false, ruleId=null, mcc=null
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(categoryRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0, "MCDONALDS", "McDonalds", List.of("Food"), false
        );
        when(transactionCategorizer.categorize(any(), eq(Collections.emptyList()))).thenReturn(List.of(response));

        CategorizationService.SuggestionResult result = categorizationService.suggestForDescription(userId, "McDonalds");

        assertFalse(result.fromRule());
        assertNull(result.ruleId());
        assertNull(result.mcc());
        assertEquals(1, result.categories().size());
        assertEquals("Food", result.categories().iterator().next().getName());
        verify(transactionCategorizer, times(1)).categorize(any(), eq(Collections.emptyList()));
        verify(categoryRepository, times(1)).save(any(Category.class));
    }

    @Test
    public void testSuggestExistingCategoryReusedAndNewCreated() {
        // T2: existing ["Food & Dining"], LLM returns ["food & dining", "Travel"] -> Food & Dining reused (no save), Travel created (one save); result size 2
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(foodCategory));

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0, "AIRPORT DINING", "Airport Dining", List.of("food & dining", "Travel"), false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        CategorizationService.SuggestionResult result = categorizationService.suggestForDescription(userId, "Airport Dining");

        assertFalse(result.fromRule());
        assertNull(result.ruleId());
        assertNull(result.mcc());
        assertEquals(2, result.categories().size());
        assertTrue(result.categories().contains(foodCategory));
        assertTrue(result.categories().stream().anyMatch(c -> c.getName().equals("Travel")));

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository, times(1)).save(captor.capture());
        assertEquals("Travel", captor.getValue().getName());
    }

    @Test
    public void testSuggestNovelCategoryNameOverTwoWordsRejected() {
        // T3: LLM returns ["Fast Food Restaurants"] (3 words, not existing) -> empty result, no save
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(categoryRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0, "MCDONALDS", "McDonalds", List.of("Fast Food Restaurants"), false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        CategorizationService.SuggestionResult result = categorizationService.suggestForDescription(userId, "McDonalds");

        assertTrue(result.categories().isEmpty());
        assertFalse(result.fromRule());
        assertNull(result.ruleId());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    public void testSuggestSanitizationRejectsInvalidCategoryNames() {
        // T4: LLM returns [""] and, separately, a 61-char name -> empty, no save
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(categoryRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        TransactionCategorizer.CategorizeItemResponse blankResponse = new TransactionCategorizer.CategorizeItemResponse(
                0, "STORE", "Store", List.of("   "), false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(blankResponse));

        CategorizationService.SuggestionResult blankResult = categorizationService.suggestForDescription(userId, "Store");
        assertTrue(blankResult.categories().isEmpty());
        verify(categoryRepository, never()).save(any());

        String longName = "A".repeat(61);
        TransactionCategorizer.CategorizeItemResponse longResponse = new TransactionCategorizer.CategorizeItemResponse(
                0, "STORE", "Store", List.of(longName), false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(longResponse));

        CategorizationService.SuggestionResult longResult = categorizationService.suggestForDescription(userId, "Store");
        assertTrue(longResult.categories().isEmpty());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    public void testSuggestNoFitReturnsEmpty() {
        // T5: noFit=true -> empty, no save
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(categoryRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0, "XYZ", "Xyz", Collections.emptyList(), true
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        CategorizationService.SuggestionResult result = categorizationService.suggestForDescription(userId, "XYZ 123");

        assertTrue(result.categories().isEmpty());
        assertFalse(result.fromRule());
        assertNull(result.ruleId());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    public void testSuggestRuleMatchReturnsRuleWithoutCallingLlm() {
        // T6: rule match -> fromRule=true, LLM never called
        CategoryRule rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setMerchantKey("SWIGGY");
        rule.setVerified(true);
        rule.setMcc("5812");
        rule.setCategories(Set.of(foodCategory));

        when(categoryRuleRepository.findByUserId(userId)).thenReturn(List.of(rule));

        CategorizationService.SuggestionResult result = categorizationService.suggestForDescription(userId, "SWIGGY FOOD DELIVERY");

        assertTrue(result.fromRule());
        assertEquals(rule.getId(), result.ruleId());
        assertEquals("5812", result.mcc());
        assertEquals(1, result.categories().size());
        assertTrue(result.categories().contains(foodCategory));
        verify(transactionCategorizer, never()).categorize(any(), any());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    public void testSuggestLlmThrowsReturnsEmptyWithoutEscaping() {
        // T7: LLM throws -> empty, no save, no exception escapes
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(categoryRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(transactionCategorizer.categorize(any(), any())).thenThrow(new RuntimeException("Gemini timeout"));

        CategorizationService.SuggestionResult result = assertDoesNotThrow(
                () -> categorizationService.suggestForDescription(userId, "McDonalds")
        );

        assertTrue(result.categories().isEmpty());
        assertFalse(result.fromRule());
        assertNull(result.ruleId());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    public void testSuggestConcurrentCreationRaceRecoversViaReload() {
        // T8: first save throws DataIntegrityViolationException; second findByUserId returns the now-existing Food -> result contains it, no exception, no second save
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Category concurrentlyCreatedFood = new Category("Food", testUser);
        concurrentlyCreatedFood.setId(UUID.randomUUID());

        // First findByUserId returns empty, second returns the concurrently created Food
        when(categoryRepository.findByUserId(userId))
                .thenReturn(Collections.emptyList())
                .thenReturn(List.of(concurrentlyCreatedFood));

        when(categoryRepository.save(any(Category.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        TransactionCategorizer.CategorizeItemResponse response = new TransactionCategorizer.CategorizeItemResponse(
                0, "MCDONALDS", "McDonalds", List.of("Food"), false
        );
        when(transactionCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        CategorizationService.SuggestionResult result = categorizationService.suggestForDescription(userId, "McDonalds");

        assertFalse(result.fromRule());
        assertNull(result.ruleId());
        assertEquals(1, result.categories().size());
        assertTrue(result.categories().contains(concurrentlyCreatedFood));
        verify(categoryRepository, times(1)).save(any());
        verify(categoryRepository, times(2)).findByUserId(userId);
    }
}
