package com.financeos.api.rules.dto;

import com.financeos.api.category.dto.CategoryResponse;
import com.financeos.domain.categorization.CategorizationService.ClearedTransaction;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The verified rule (same fields as RuleResponse) plus what the verify cleared, to send back to unverify as `restore`. */
public record VerifyRuleResponse(
        UUID id,
        String merchantKey,
        String matchType,
        @Nullable String displayName,
        List<CategoryResponse> categories,
        boolean verified,
        String source,
        int appliedCount,
        @Nullable Instant lastAppliedAt,
        Instant createdAt,
        @Nullable String mcc,
        List<ClearedTransaction> clearedTransactions
) {
    public static VerifyRuleResponse from(RuleResponse r, List<ClearedTransaction> cleared) {
        return new VerifyRuleResponse(r.id(), r.merchantKey(), r.matchType(), r.displayName(), r.categories(),
                r.verified(), r.source(), r.appliedCount(), r.lastAppliedAt(), r.createdAt(), r.mcc(), cleared);
    }
}
