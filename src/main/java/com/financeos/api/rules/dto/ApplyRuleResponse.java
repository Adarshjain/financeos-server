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
        String categoryKey,
        String subcategoryKey,
        // true = every transaction the pattern matches; false = the transactions the user picked.
        boolean allMatches,
        int requested,
        // Skipped: rows whose category the user set, rows the pattern no longer matches, rows whose direction the category does not fit.
        int skippedUserSet,
        int skippedNoLongerMatching,
        int skippedWrongDirection,
        int notFound,
        List<RuleMatchService.AppliedTransaction> transactions) {

    public ApplyRuleResponse(int appliedCount) {
        this(appliedCount, null, null, null, null, null, null, false, 0, 0, 0, 0, 0, List.of());
    }
}
