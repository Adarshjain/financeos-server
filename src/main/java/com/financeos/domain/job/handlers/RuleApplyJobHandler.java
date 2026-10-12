package com.financeos.domain.job.handlers;

import com.financeos.api.rules.dto.ApplyRuleResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.category.Category;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.RuleMatchService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RuleApplyJobHandler implements JobHandler {

    private final CategoryRuleRepository categoryRuleRepository;
    private final RuleMatchService ruleMatchService;

    public RuleApplyJobHandler(CategoryRuleRepository categoryRuleRepository,
                               RuleMatchService ruleMatchService) {
        this.categoryRuleRepository = categoryRuleRepository;
        this.ruleMatchService = ruleMatchService;
    }

    @Override
    public JobType type() {
        return JobType.RULE_APPLY;
    }

    @Override
    public Object execute(JobExecutionContext ctx) throws Exception {
        RuleApplyPayload payload = ctx.payload(RuleApplyPayload.class);
        CategoryRule rule = categoryRuleRepository.findWithCategoriesById(payload.ruleId())
                .orElseThrow(() -> new ResourceNotFoundException("Rule", payload.ruleId()));

        if (ctx.getUserId() != null && !rule.getUser().getId().equals(ctx.getUserId())) {
            throw new ValidationException("SECURITY_MISMATCH: Rule does not belong to user " + ctx.getUserId());
        }

        ctx.checkCancelled();

        boolean all = Boolean.TRUE.equals(payload.all());
        RuleMatchService.ApplyOutcome outcome = all
                ? ruleMatchService.applyToAllMatchesDetailed(rule.getId())
                : ruleMatchService.applyToTransactionsDetailed(rule.getId(), payload.transactionIds());

        List<String> categories = rule.getCategories().stream()
                .map(Category::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        String ruleName = rule.getDisplayName() != null && !rule.getDisplayName().isBlank()
                ? rule.getDisplayName()
                : rule.getMerchantKey();

        return new ApplyRuleResponse(
                outcome.applied(),
                rule.getId(),
                ruleName,
                rule.getMatchType(),
                rule.getMerchantKey(),
                categories,
                all,
                outcome.requested(),
                outcome.skippedManuallyReviewed(),
                outcome.skippedNoLongerMatching(),
                outcome.notFound(),
                outcome.appliedSample());
    }
}
