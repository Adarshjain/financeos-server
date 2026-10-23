package com.financeos.api.transaction.dto;

import com.financeos.domain.categorization.CategorizationService.ClearedTransaction;
import java.util.List;

/**
 * approvedTransactions: for a clearing review, each row this call cleared with its prior review state and
 * the updatedAt stamp it was left with (verifiedUpdatedAt); send them to undo-approve. Empty otherwise.
 */
public record BatchReviewResponse(
    List<String> succeededIds,
    List<String> skippedIds,
    List<BatchFailure> failures,
    List<VerifiedRuleChange> verifiedRules,
    List<ClearedTransaction> approvedTransactions
) {
    public BatchReviewResponse(List<String> succeededIds, List<String> skippedIds,
                               List<BatchFailure> failures, List<VerifiedRuleChange> verifiedRules) {
        this(succeededIds, skippedIds, failures, verifiedRules, List.of());
    }
}
