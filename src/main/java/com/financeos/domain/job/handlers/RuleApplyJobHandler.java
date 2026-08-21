package com.financeos.domain.job.handlers;

import com.financeos.api.rules.dto.ApplyRuleResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.CategoryRuleRepository;
import com.financeos.domain.categorization.RuleMatchService;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.job.JobHandler;
import com.financeos.domain.job.JobType;
import org.springframework.stereotype.Component;

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
        int applied = all
                ? ruleMatchService.applyToAllMatches(rule.getId())
                : ruleMatchService.applyToTransactions(rule.getId(), payload.transactionIds());

        return new ApplyRuleResponse(applied);
    }
}
