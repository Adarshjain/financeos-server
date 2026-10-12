package com.financeos.domain.categorization;

import com.financeos.domain.category.Category;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The detailed apply reports why each requested transaction did or did not take the rule. */
class RuleMatchServiceApplyOutcomeTest {

    private TransactionRepository transactionRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private RuleMatchService ruleMatchService;
    private User user;
    private UUID userId;
    private CategoryRule rule;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        ruleMatchService = new RuleMatchService(transactionRepository, categoryRuleRepository, mock(ReviewStatusManager.class));

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);

        Category food = new Category("Food & Dining", user);
        food.setId(UUID.randomUUID());

        rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setUser(user);
        rule.setMerchantKey("SWIGGY");
        rule.setMatchType(MatchType.CONTAINS);
        rule.setCategories(Set.of(food));

        when(categoryRuleRepository.findWithCategoriesById(rule.getId())).thenReturn(Optional.of(rule));
        when(categoryRuleRepository.save(any(CategoryRule.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Transaction txn(String sourcedDescription) {
        Transaction txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(user);
        txn.setSourcedDescription(sourcedDescription);
        txn.setCategories(new HashSet<>());
        txn.setDate(LocalDate.of(2026, 10, 1));
        txn.setAmount(new BigDecimal("249.00"));
        txn.setType(TransactionType.DEBIT);
        return txn;
    }

    @Test
    void everyRequestedTransactionIsAccountedFor() {
        Transaction applies = txn("UPI SWIGGY ORDER");
        Transaction reviewed = txn("SWIGGY DINEOUT");
        reviewed.setReviewType(ReviewType.MANUALLY_REVIEWED);
        Transaction drifted = txn("AMAZON PAY");
        UUID missing = UUID.randomUUID();
        List<UUID> ids = List.of(applies.getId(), reviewed.getId(), drifted.getId(), missing);
        when(transactionRepository.findAllByIdInAndUserId(ids, userId)).thenReturn(List.of(applies, reviewed, drifted));

        RuleMatchService.ApplyOutcome outcome = ruleMatchService.applyToTransactionsDetailed(rule.getId(), ids);

        assertThat(outcome.requested()).isEqualTo(4);
        assertThat(outcome.applied()).isEqualTo(1);
        assertThat(outcome.skippedManuallyReviewed()).isEqualTo(1);
        assertThat(outcome.skippedNoLongerMatching()).isEqualTo(1);
        assertThat(outcome.notFound()).isEqualTo(1);
        assertThat(outcome.appliedSample()).containsExactly(new RuleMatchService.AppliedTransaction(
                applies.getId(), LocalDate.of(2026, 10, 1), new BigDecimal("249.00"), TransactionType.DEBIT, "UPI SWIGGY ORDER"));
    }

    @Test
    void theSampleShowsTheUsersOwnDescriptionWhenSet() {
        Transaction applies = txn("UPI SWIGGY ORDER");
        applies.setDescription("Dinner with friends");
        when(transactionRepository.findAllByIdInAndUserId(List.of(applies.getId()), userId)).thenReturn(List.of(applies));

        RuleMatchService.ApplyOutcome outcome = ruleMatchService.applyToTransactionsDetailed(rule.getId(), List.of(applies.getId()));

        assertThat(outcome.appliedSample().get(0).description()).isEqualTo("Dinner with friends");
    }

    @Test
    void theSampleIsCappedButTheCountIsNot() {
        List<Transaction> txns = new ArrayList<>();
        for (int i = 0; i < RuleMatchService.APPLY_OUTCOME_SAMPLE_SIZE + 10; i++) {
            txns.add(txn("SWIGGY " + i));
        }
        List<UUID> ids = txns.stream().map(Transaction::getId).toList();
        when(transactionRepository.findAllByIdInAndUserId(ids, userId)).thenReturn(txns);

        RuleMatchService.ApplyOutcome outcome = ruleMatchService.applyToTransactionsDetailed(rule.getId(), ids);

        assertThat(outcome.applied()).isEqualTo(RuleMatchService.APPLY_OUTCOME_SAMPLE_SIZE + 10);
        assertThat(outcome.appliedSample()).hasSize(RuleMatchService.APPLY_OUTCOME_SAMPLE_SIZE);
    }

    @Test
    void applyToAllMatchesRequestsEveryCurrentMatch() {
        Transaction a = txn("SWIGGY ONE");
        Transaction b = txn("swiggy two");
        when(transactionRepository.findRuleMatchCandidates(userId, ReviewType.MANUALLY_REVIEWED))
                .thenReturn(List.of(candidate(a), candidate(b), candidate(txn("ZOMATO"))));
        when(transactionRepository.findAllByIdInAndUserId(eq(List.of(a.getId(), b.getId())), eq(userId))).thenReturn(List.of(a, b));

        RuleMatchService.ApplyOutcome outcome = ruleMatchService.applyToAllMatchesDetailed(rule.getId());

        assertThat(outcome.requested()).isEqualTo(2);
        assertThat(outcome.applied()).isEqualTo(2);
        assertThat(outcome.notFound()).isZero();
    }

    @Test
    void theIntVariantsStillReturnTheAppliedCount() {
        Transaction applies = txn("UPI SWIGGY ORDER");
        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId))).thenReturn(List.of(applies));

        assertThat(ruleMatchService.applyToTransactions(rule.getId(), List.of(applies.getId(), UUID.randomUUID()))).isEqualTo(1);
    }

    private static TransactionRepository.RuleMatchCandidate candidate(Transaction txn) {
        return new TransactionRepository.RuleMatchCandidate() {
            @Override
            public UUID getId() {
                return txn.getId();
            }

            @Override
            public String getSourcedDescription() {
                return txn.getSourcedDescription();
            }
        };
    }
}
