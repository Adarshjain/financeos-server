package com.financeos.domain.job.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.rules.dto.ApplyRuleResponse;
import com.financeos.domain.category.Category;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.MatchType;
import com.financeos.domain.categorization.RuleMatchService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobService;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The RULE_APPLY result names the rule, what it sets, and what happened to the transactions. */
class RuleApplyJobHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CategoryRuleRepository categoryRuleRepository;
    private RuleMatchService ruleMatchService;
    private RuleApplyJobHandler handler;
    private UUID userId;
    private CategoryRule rule;

    @BeforeEach
    void setUp() {
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        ruleMatchService = mock(RuleMatchService.class);
        handler = new RuleApplyJobHandler(categoryRuleRepository, ruleMatchService);

        userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        Category travel = new Category("travel", user);
        Category food = new Category("Food", user);

        rule = new CategoryRule();
        rule.setId(UUID.randomUUID());
        rule.setUser(user);
        rule.setMerchantKey("UBER");
        rule.setMatchType(MatchType.STARTS_WITH);
        rule.setCategories(Set.of(travel, food));
        when(categoryRuleRepository.findWithCategoriesById(rule.getId())).thenReturn(Optional.of(rule));
    }

    private JobExecutionContext ctx(RuleApplyPayload payload) throws Exception {
        return new JobExecutionContext(UUID.randomUUID(), userId, objectMapper.writeValueAsString(payload),
                mock(JobService.class), objectMapper);
    }

    @Test
    void applyToAllMatchesReportsRuleAndOutcome() throws Exception {
        RuleMatchService.AppliedTransaction sample = new RuleMatchService.AppliedTransaction(
                UUID.randomUUID(), LocalDate.of(2026, 10, 2), new BigDecimal("310"), TransactionType.DEBIT, "UBER TRIP");
        when(ruleMatchService.applyToAllMatchesDetailed(rule.getId()))
                .thenReturn(new RuleMatchService.ApplyOutcome(5, 3, 1, 1, 0, List.of(sample)));

        ApplyRuleResponse result = (ApplyRuleResponse) handler.execute(ctx(new RuleApplyPayload(rule.getId(), true, null)));

        assertThat(result.appliedCount()).isEqualTo(3);
        assertThat(result.ruleId()).isEqualTo(rule.getId());
        assertThat(result.ruleName()).isEqualTo("UBER");
        assertThat(result.matchType()).isEqualTo(MatchType.STARTS_WITH);
        assertThat(result.pattern()).isEqualTo("UBER");
        assertThat(result.categories()).containsExactly("Food", "travel");
        assertThat(result.allMatches()).isTrue();
        assertThat(result.requested()).isEqualTo(5);
        assertThat(result.skippedManuallyReviewed()).isEqualTo(1);
        assertThat(result.skippedNoLongerMatching()).isEqualTo(1);
        assertThat(result.notFound()).isZero();
        assertThat(result.transactions()).containsExactly(sample);
    }

    @Test
    void pickedTransactionsUseTheRuleDisplayName() throws Exception {
        rule.setDisplayName("Cab rides");
        List<UUID> picked = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(ruleMatchService.applyToTransactionsDetailed(rule.getId(), picked))
                .thenReturn(new RuleMatchService.ApplyOutcome(2, 1, 0, 0, 1, List.of()));

        ApplyRuleResponse result = (ApplyRuleResponse) handler.execute(ctx(new RuleApplyPayload(rule.getId(), false, picked)));

        assertThat(result.ruleName()).isEqualTo("Cab rides");
        assertThat(result.allMatches()).isFalse();
        assertThat(result.requested()).isEqualTo(2);
        assertThat(result.notFound()).isEqualTo(1);
    }

    @Test
    void aBlankDisplayNameFallsBackToThePattern() throws Exception {
        rule.setDisplayName("   ");
        when(ruleMatchService.applyToAllMatchesDetailed(rule.getId()))
                .thenReturn(new RuleMatchService.ApplyOutcome(0, 0, 0, 0, 0, List.of()));

        ApplyRuleResponse result = (ApplyRuleResponse) handler.execute(ctx(new RuleApplyPayload(rule.getId(), true, null)));

        assertThat(result.ruleName()).isEqualTo("UBER");
        assertThat(result.appliedCount()).isZero();
    }
}
