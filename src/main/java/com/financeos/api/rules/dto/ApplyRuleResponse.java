package com.financeos.api.rules.dto;

import com.financeos.domain.categorization.MatchType;
import com.financeos.domain.categorization.RuleMatchService;

import java.util.List;
import java.util.UUID;

/**
 * RULE_APPLY job result: which rule ran, what it sets, and what happened to the transactions it
 * was pointed at. {@code appliedCount} keeps its original meaning.
 */
public record ApplyRuleResponse(
        int appliedCount,
        UUID ruleId,
        String ruleName,
        MatchType matchType,
        String pattern,
        List<String> categories,
        // true = every transaction the pattern matches; false = the transactions the user picked.
        boolean allMatches,
        int requested,
        int skippedManuallyReviewed,
        int skippedNoLongerMatching,
        int notFound,
        List<RuleMatchService.AppliedTransaction> transactions) {

    public ApplyRuleResponse(int appliedCount) {
        this(appliedCount, null, null, null, null, List.of(), false, 0, 0, 0, 0, List.of());
    }
}
