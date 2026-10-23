package com.financeos.api.rules.dto;

import com.financeos.domain.categorization.CategorizationService.RuleVerification;
import java.util.List;

/** verifiedCount counts only rules that were unverified before the call; verifiedRules is what undo needs, per such rule. */
public record BulkVerifyRulesResponse(int verifiedCount, List<RuleVerification> verifiedRules) {}
