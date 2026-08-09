package com.financeos.domain.categorization;

import com.financeos.domain.category.Category;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

public class RuleMatchServiceTest {

    private TransactionRepository transactionRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private ReviewStatusManager reviewStatusManager;
    private RuleMatchService ruleMatchService;

    private User testUser;
    private UUID userId;
    private Category foodCategory;
    private CategoryRule rule;

    @BeforeEach
    public void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        reviewStatusManager = mock(ReviewStatusManager.class);
        ruleMatchService = new RuleMatchService(transactionRepository, categoryRuleRepository, reviewStatusManager);

        userId = UUID.randomUUID();
        testUser = new User();
        testUser.setId(userId);

        foodCategory = new Category("Food & Dining", testUser);
        foodCategory.setId(UUID.randomUUID());

        rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setUser(testUser);
        rule.setMerchantKey("SWIGGY");
        rule.setMatchType(MatchType.CONTAINS);
        rule.setSource("USER");
        rule.setVerified(true);
        rule.setCategories(Set.of(foodCategory));
        rule.setAppliedCount(0);

        when(categoryRuleRepository.findWithCategoriesById(rule.getId())).thenReturn(Optional.of(rule));
        when(categoryRuleRepository.save(any(CategoryRule.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static TransactionRepository.RuleMatchCandidate candidate(UUID id, String sourcedDescription) {
        return new TransactionRepository.RuleMatchCandidate() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getSourcedDescription() {
                return sourcedDescription;
            }
        };
    }

    private Transaction txn(UUID id, String sourcedDescription) {
        Transaction txn = new Transaction();
        txn.setId(id);
        txn.setUser(testUser);
        txn.setSourcedDescription(sourcedDescription);
        txn.setCategories(new HashSet<>());
        return txn;
    }

    @Test
    public void findMatchesFiltersAndPaginates() {
        UUID match1 = UUID.randomUUID();
        UUID match2 = UUID.randomUUID();
        UUID miss = UUID.randomUUID();
        when(transactionRepository.findRuleMatchCandidates(userId, ReviewType.MANUALLY_REVIEWED))
                .thenReturn(List.of(
                        candidate(match1, "UPI SWIGGY ORDER 1"),
                        candidate(miss, "AMAZON PAY"),
                        candidate(match2, "swiggy instamart")));

        Transaction txn1 = txn(match1, "UPI SWIGGY ORDER 1");
        when(transactionRepository.findAllByIdInAndUserId(List.of(match1), userId)).thenReturn(List.of(txn1));

        Page<RuleMatchService.MatchedTransaction> page =
                ruleMatchService.findMatches(userId, MatchType.CONTAINS, "SWIGGY", PageRequest.of(0, 1));

        assertEquals(2, page.getTotalElements());
        assertEquals(1, page.getContent().size());
        assertEquals(match1, page.getContent().get(0).id());
    }

    @Test
    public void applyToTransactionsSetsRuleCategoriesAndLink() {
        UUID txnId = UUID.randomUUID();
        Transaction txn = txn(txnId, "UPI SWIGGY ORDER");
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.getReviewReasons().add(ReviewReason.CATEGORY_UNVERIFIED);

        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId))).thenReturn(List.of(txn));

        int applied = ruleMatchService.applyToTransactions(rule.getId(), List.of(txnId));

        assertEquals(1, applied);
        assertEquals(1, txn.getCategories().size());
        assertTrue(txn.getCategories().stream().anyMatch(tc -> tc.getCategory().equals(foodCategory)));
        assertEquals(rule, txn.getAppliedRule());
        assertEquals(1, rule.getAppliedCount());
        assertNotNull(rule.getLastAppliedAt());
        verify(reviewStatusManager).clearReason(txn, ReviewReason.CATEGORY_UNVERIFIED, ReviewType.AUTO_REVIEWED);
        verify(transactionRepository).saveAll(List.of(txn));
    }

    @Test
    public void applySkipsManuallyReviewedAndNonMatching() {
        Transaction manuallyReviewed = txn(UUID.randomUUID(), "UPI SWIGGY ORDER");
        manuallyReviewed.setReviewType(ReviewType.MANUALLY_REVIEWED);

        Transaction nonMatching = txn(UUID.randomUUID(), "AMAZON PAY");
        Transaction manualOnly = txn(UUID.randomUUID(), null); // manual txn: no sourcedDescription
        manualOnly.setDescription("SWIGGY DINNER");

        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId)))
                .thenReturn(List.of(manuallyReviewed, nonMatching, manualOnly));

        int applied = ruleMatchService.applyToTransactions(rule.getId(),
                List.of(manuallyReviewed.getId(), nonMatching.getId(), manualOnly.getId()));

        assertEquals(0, applied);
        assertTrue(manuallyReviewed.getCategories().isEmpty());
        assertTrue(nonMatching.getCategories().isEmpty());
        assertTrue(manualOnly.getCategories().isEmpty());
        assertEquals(0, rule.getAppliedCount());
        verify(transactionRepository, never()).saveAll(anyList());
        verify(categoryRuleRepository, never()).save(any());
    }

    @Test
    public void applyDoesNotTouchReviewStateWithoutCategoryUnverifiedFlag() {
        // A transaction with some other review reason (or none) keeps its review state;
        // apply only ever clears CATEGORY_UNVERIFIED.
        Transaction txn = txn(UUID.randomUUID(), "SWIGGY ORDER");
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.getReviewReasons().add(ReviewReason.UNRECONCILED);

        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId))).thenReturn(List.of(txn));

        int applied = ruleMatchService.applyToTransactions(rule.getId(), List.of(txn.getId()));

        assertEquals(1, applied);
        assertEquals(rule, txn.getAppliedRule());
        verify(reviewStatusManager, never()).clearReason(any(), any(), any());
    }

    @Test
    public void applyToAllMatchesRecomputesMatchesServerSide() {
        UUID matchId = UUID.randomUUID();
        when(transactionRepository.findRuleMatchCandidates(userId, ReviewType.MANUALLY_REVIEWED))
                .thenReturn(List.of(
                        candidate(matchId, "SWIGGY ORDER"),
                        candidate(UUID.randomUUID(), "AMAZON PAY")));

        Transaction txn = txn(matchId, "SWIGGY ORDER");
        when(transactionRepository.findAllByIdInAndUserId(List.of(matchId), userId)).thenReturn(List.of(txn));

        int applied = ruleMatchService.applyToAllMatches(rule.getId());

        assertEquals(1, applied);
        assertEquals(rule, txn.getAppliedRule());
    }
}
