package com.financeos.api.rules.dto;

import com.financeos.api.category.dto.CategoryRef;
import com.financeos.api.category.dto.SubcategoryRef;
import com.financeos.domain.categorization.CategoryRule;
import com.financeos.domain.categorization.MatchType;
import com.financeos.domain.category.CategoryCatalog;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

public record RuleResponse(
        UUID id,
        String merchantKey,
        String matchType,
        @Nullable String displayName,
        CategoryRef category,
        @Nullable SubcategoryRef subcategory,
        int appliedCount,
        @Nullable Instant lastAppliedAt,
        Instant createdAt,
        @Nullable String mcc
) {
    public static RuleResponse from(CategoryRule rule) {
        return from(rule, CategoryCatalog.shared());
    }

    public static RuleResponse from(CategoryRule rule, CategoryCatalog catalog) {
        return new RuleResponse(
                rule.getId(),
                rule.getMerchantKey(),
                rule.getMatchType() != null ? rule.getMatchType().name() : MatchType.CONTAINS.name(),
                rule.getDisplayName(),
                CategoryRef.of(catalog, rule.getCategoryKey()),
                SubcategoryRef.of(catalog, rule.getSubcategoryKey()),
                rule.getAppliedCount(),
                rule.getLastAppliedAt(),
                rule.getCreatedAt(),
                rule.getMcc()
        );
    }
}
