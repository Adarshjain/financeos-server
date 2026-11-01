package com.financeos.domain.categorization;

import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

public class RuleMatchServiceTest {

    private TransactionRepository transactionRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private RuleMatchService ruleMatchService;

    private User testUser;
    private UUID userId;
    private CategoryRule rule;

    @BeforeEach
    public void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        ruleMatchService = new RuleMatchService(transactionRepository, categoryRuleRepository,
                com.financeos.support.TestCategories.writer(categoryRuleRepository));

        userId = UUID.randomUUID();
        testUser = new User();
        testUser.setId(userId);

        rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setUser(testUser);
        rule.setMerchantKey("SWIGGY");
        rule.setMatchType(MatchType.CONTAINS);
        rule.setCategoryKey("food");
        rule.setSubcategoryKey("food.delivery");
        rule.setAppliedCount(0);

        when(categoryRuleRepository.findById(rule.getId())).thenReturn(Optional.of(rule));
        when(categoryRuleRepository.save(any(CategoryRule.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static TransactionRepository.RuleMatchCandidate candidate(UUID id, String sourcedDescription) {
        return candidate(id, sourcedDescription, null);
    }

    private static TransactionRepository.RuleMatchCandidate candidate(UUID id, String sourcedDescription,
                                                                      CategorySource categorySource) {
        return new TransactionRepository.RuleMatchCandidate() {
            @Override
            public CategorySource getCategorySource() {
                return categorySource;
            }

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
        txn.setType(TransactionType.DEBIT);
        com.financeos.domain.account.Account account = new com.financeos.domain.account.Account();
        account.setId(UUID.randomUUID());
        account.setName("Main Bank");
        txn.setAccount(account);
        return txn;
    }

    @Test
    public void findMatchesFiltersAndPaginates() {
        UUID match1 = UUID.randomUUID();
        UUID match2 = UUID.randomUUID();
        UUID miss = UUID.randomUUID();
        when(transactionRepository.findRuleMatchCandidates(userId))
                .thenReturn(List.of(
                        candidate(match1, "UPI SWIGGY ORDER 1"),
                        candidate(miss, "AMAZON PAY"),
                        candidate(match2, "swiggy instamart")));

        Transaction txn1 = txn(match1, "UPI SWIGGY ORDER 1");
        when(transactionRepository.findAllByIdInAndUserId(List.of(match1), userId)).thenReturn(List.of(txn1));

        RuleMatchService.MatchPage result =
                ruleMatchService.findMatches(userId, MatchType.CONTAINS, "SWIGGY", PageRequest.of(0, 1));

        assertEquals(2, result.matches().getTotalElements());
        assertEquals(2, result.matchCount());
        assertEquals(1, result.matches().getContent().size());
        assertEquals(match1, result.matches().getContent().get(0).id());
    }

    @Test
    public void findMatchesNamesTheRuleEachMatchIsLinkedTo() {
        rule.setDisplayName("Food delivery");
        CategoryRule otherRule = new CategoryRule();
        otherRule.setId(UUID.randomUUID());
        otherRule.setUser(testUser);
        otherRule.setMerchantKey("INSTAMART");
        otherRule.setMatchType(MatchType.CONTAINS);
        otherRule.setDisplayName("  ");

        UUID linkedHere = UUID.randomUUID();
        UUID linkedElsewhere = UUID.randomUUID();
        UUID unlinked = UUID.randomUUID();
        when(transactionRepository.findRuleMatchCandidates(userId))
                .thenReturn(List.of(
                        candidate(linkedHere, "UPI SWIGGY ORDER"),
                        candidate(linkedElsewhere, "SWIGGY INSTAMART"),
                        candidate(unlinked, "SWIGGY DINEOUT")));

        Transaction here = txn(linkedHere, "UPI SWIGGY ORDER");
        here.setAppliedRule(rule);
        Transaction elsewhere = txn(linkedElsewhere, "SWIGGY INSTAMART");
        elsewhere.setAppliedRule(otherRule);
        Transaction none = txn(unlinked, "SWIGGY DINEOUT");
        when(transactionRepository.findAllByIdInAndUserId(List.of(linkedHere, linkedElsewhere, unlinked), userId))
                .thenReturn(List.of(here, elsewhere, none));

        List<RuleMatchService.MatchedTransaction> content = ruleMatchService
                .findMatches(userId, MatchType.CONTAINS, "SWIGGY", PageRequest.of(0, 10))
                .matches().getContent();

        assertEquals(rule.getId(), content.get(0).appliedRuleId());
        assertEquals("Food delivery", content.get(0).appliedRuleName());
        assertEquals(otherRule.getId(), content.get(1).appliedRuleId());
        assertEquals("INSTAMART", content.get(1).appliedRuleName(), "blank display name falls back to the pattern");
        assertNull(content.get(2).appliedRuleId());
        assertNull(content.get(2).appliedRuleName());
    }

    @Test
    public void findMatchesCountsTheOnesTheUserCategorised() {
        UUID byUser = UUID.randomUUID();
        UUID byRule = UUID.randomUUID();
        UUID plain = UUID.randomUUID();
        when(transactionRepository.findRuleMatchCandidates(userId))
                .thenReturn(List.of(
                        candidate(byUser, "SWIGGY A", CategorySource.USER),
                        candidate(byRule, "SWIGGY B", CategorySource.RULE),
                        candidate(plain, "SWIGGY C"),
                        candidate(UUID.randomUUID(), "AMAZON PAY", CategorySource.USER)));
        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId))).thenReturn(List.of());

        RuleMatchService.MatchPage result =
                ruleMatchService.findMatches(userId, MatchType.CONTAINS, "SWIGGY", PageRequest.of(0, 10));

        assertEquals(3, result.matchCount());
        assertEquals(1, result.userSetCount(), "only matching rows count, and only the user's");
    }

    @Test
    public void applyToTransactionsSetsRuleCategoryAndLink() {
        UUID txnId = UUID.randomUUID();
        Transaction txn = txn(txnId, "UPI SWIGGY ORDER");

        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId))).thenReturn(List.of(txn));

        int applied = ruleMatchService.applyToTransactions(rule.getId(), List.of(txnId));

        assertEquals(1, applied);
        assertEquals("food", txn.getCategoryKey());
        assertEquals("food.delivery", txn.getSubcategoryKey());
        assertEquals(CategorySource.RULE, txn.getCategorySource());
        assertNotNull(txn.getCategorySetAt());
        assertEquals(rule, txn.getAppliedRule());
        assertEquals(1, rule.getAppliedCount());
        assertNotNull(rule.getLastAppliedAt());
        verify(transactionRepository).saveAll(List.of(txn));
    }

    @Test
    public void applySkipsUserSetAndNonMatching() {
        Transaction userSet = txn(UUID.randomUUID(), "UPI SWIGGY ORDER");
        userSet.setCategoryKey("shopping");
        userSet.setCategorySource(CategorySource.USER);

        Transaction nonMatching = txn(UUID.randomUUID(), "AMAZON PAY");
        Transaction manualOnly = txn(UUID.randomUUID(), null); // manual txn: no sourcedDescription
        manualOnly.setDescription("SWIGGY DINNER");

        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId)))
                .thenReturn(List.of(userSet, nonMatching, manualOnly));

        RuleMatchService.ApplyOutcome outcome = ruleMatchService.applyToTransactionsDetailed(rule.getId(),
                List.of(userSet.getId(), nonMatching.getId(), manualOnly.getId()));

        assertEquals(0, outcome.applied());
        assertEquals(1, outcome.skippedUserSet());
        assertEquals(2, outcome.skippedNoLongerMatching());
        assertEquals("shopping", userSet.getCategoryKey(), "the user's category is never overwritten");
        assertEquals(CategorySource.USER, userSet.getCategorySource());
        assertNull(nonMatching.getCategoryKey());
        assertNull(manualOnly.getCategoryKey());
        assertEquals(0, rule.getAppliedCount());
        verify(transactionRepository, never()).saveAll(anyList());
        verify(categoryRuleRepository, never()).save(any());
    }

    @Test
    public void applySkipsRowsWhoseDirectionTheCategoryDoesNotFit() {
        Transaction credit = txn(UUID.randomUUID(), "SWIGGY REFUND");
        credit.setType(TransactionType.CREDIT); // food is money-out only
        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId))).thenReturn(List.of(credit));

        RuleMatchService.ApplyOutcome outcome =
                ruleMatchService.applyToTransactionsDetailed(rule.getId(), List.of(credit.getId()));

        assertEquals(0, outcome.applied());
        assertEquals(1, outcome.skippedWrongDirection());
        assertNull(credit.getCategoryKey());
    }

    @Test
    public void applyToAllMatchesRecomputesMatchesServerSide() {
        UUID matchId = UUID.randomUUID();
        when(transactionRepository.findRuleMatchCandidates(userId))
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
