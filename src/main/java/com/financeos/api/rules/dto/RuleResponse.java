package com.financeos.api.rules.dto;

import com.financeos.api.category.dto.CategoryResponse;
import com.financeos.domain.categorization.CategoryRule;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RuleResponse(
        UUID id,
        String merchantKey,
        String displayName,
        List<CategoryResponse> categories,
        boolean verified,
        String source,
        int appliedCount,
        Instant lastAppliedAt,
        Instant createdAt,
        String mcc
) {
    public static RuleResponse from(CategoryRule rule) {
        List<CategoryResponse> categoryResponses = rule.getCategories().stream()
                .map(CategoryResponse::from)
                .toList();
        return new RuleResponse(
                rule.getId(),
                rule.getMerchantKey(),
                rule.getDisplayName(),
                categoryResponses,
                rule.isVerified(),
                rule.getSource(),
                rule.getAppliedCount(),
                rule.getLastAppliedAt(),
                rule.getCreatedAt(),
                rule.getMcc()
        );
    }
}
