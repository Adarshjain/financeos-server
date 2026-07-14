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

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class CategorizationServiceTest {

    private CategoryRuleRepository categoryRuleRepository;
    private CategoryRepository categoryRepository;
    private TransactionRepository transactionRepository;
    private UserRepository userRepository;
    private GeminiCategorizer geminiCategorizer;
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
        geminiCategorizer = mock(GeminiCategorizer.class);
        reviewStatusManager = mock(ReviewStatusManager.class);

        categorizationService = new CategorizationService(
                categoryRuleRepository,
                categoryRepository,
                transactionRepository,
                userRepository,
                geminiCategorizer,
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
        txn.setDescription("SWIGGY FOOD DELIVERY");
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
        txn.setDescription("SWIGGY FOOD DELIVERY");
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
        txn.setDescription("AMAZON PAY INDIA");
        txn.setCategories(new HashSet<>());

        GeminiCategorizer.CategorizeItemResponse response = new GeminiCategorizer.CategorizeItemResponse(
                0,
                "AMAZON",
                "Amazon",
                List.of("Shopping"),
                false
        );
        when(geminiCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        CategoryRule newRule = new CategoryRule();
        newRule.setId(UUID.randomUUID());
        newRule.setMerchantKey("AMAZON");
        newRule.setVerified(false);
        newRule.setCategories(Set.of(shoppingCategory));

        when(categoryRuleRepository.findByUserIdAndMerchantKey(userId, "AMAZON")).thenReturn(Optional.empty());
        when(categoryRuleRepository.saveAndFlush(any())).thenReturn(newRule);

        categorizationService.batchCategorize(List.of(txn));

        assertEquals(1, txn.getCategories().size());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
        assertNotNull(txn.getAppliedRule());
    }

    @Test
    public void testBatchCategorizeLlmFailure() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());
        when(geminiCategorizer.categorize(any(), any())).thenReturn(Collections.emptyList()); // Failure / Empty

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setDescription("AMAZON PAY INDIA");
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
        txn.setDescription("XYZ PAYMENTS");
        txn.setCategories(new HashSet<>());

        GeminiCategorizer.CategorizeItemResponse response = new GeminiCategorizer.CategorizeItemResponse(
                0,
                "XYZ",
                "Xyz",
                Collections.emptyList(),
                true // noFit = true
        );
        when(geminiCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        assertTrue(txn.getCategories().isEmpty());
        assertNull(txn.getAppliedRule());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testHallucinatedCategoryDiscarded() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        GeminiCategorizer.CategorizeItemResponse response = new GeminiCategorizer.CategorizeItemResponse(
                0,
                "SWIGGY",
                "Swiggy",
                List.of("Gifts & Charities"), // Hallucinated category (not in foodCategory / shoppingCategory)
                false
        );
        when(geminiCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        categorizationService.batchCategorize(List.of(txn));

        // Should fall back to LLM failure (uncategorized + CATEGORY_UNVERIFIED)
        assertTrue(txn.getCategories().isEmpty());
        verify(reviewStatusManager, times(1)).addReason(txn, ReviewReason.CATEGORY_UNVERIFIED);
    }

    @Test
    public void testHallucinatedMerchantKeyDiscarded() {
        when(categoryRuleRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        Transaction txn = new Transaction();
        txn.setUser(testUser);
        txn.setDescription("SWIGGY DELIVERY");
        txn.setCategories(new HashSet<>());

        GeminiCategorizer.CategorizeItemResponse response = new GeminiCategorizer.CategorizeItemResponse(
                0,
                "AMAZON", // Hallucinated merchant key (AMAZON is not in "SWIGGY DELIVERY")
                "Amazon",
                List.of("Shopping"),
                false
        );
        when(geminiCategorizer.categorize(any(), any())).thenReturn(List.of(response));

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

        GeminiCategorizer.CategorizeItemResponse response = new GeminiCategorizer.CategorizeItemResponse(
                0,
                "AMAZON",
                "Amazon",
                List.of("Shopping"),
                false
        );
        when(geminiCategorizer.categorize(any(), any())).thenReturn(List.of(response));

        when(categoryRuleRepository.findByUserIdAndMerchantKey(userId, "AMAZON")).thenReturn(Optional.empty());

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
        txn.setDescription("SWIGGY FOOD DELIVERY");
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
        txn.setDescription("SWIGGY FOOD DELIVERY");
        txn.setCategories(new HashSet<>());
        txn.setMcc("5411"); // Pre-existing MCC from card statement

        categorizationService.batchCategorize(List.of(txn));

        assertEquals("5411", txn.getMcc());
        assertEquals(rule, txn.getAppliedRule());
    }
}
