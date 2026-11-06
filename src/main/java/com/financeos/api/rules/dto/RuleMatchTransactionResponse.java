package com.financeos.api.rules.dto;

import com.financeos.api.category.dto.CategoryRef;
import com.financeos.api.category.dto.SubcategoryRef;
import com.financeos.domain.categorization.RuleMatchService;
import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.transaction.CategorySource;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RuleMatchTransactionResponse(
        UUID id,
        LocalDate date,
        @Nullable BigDecimal amount,
        String type,
        String sourcedDescription,
        @Nullable CategoryRef category,
        @Nullable SubcategoryRef subcategory,
        @Nullable CategorySource categorySource,
        @Nullable UUID appliedRuleId,
        @Nullable String appliedRuleName,
        UUID accountId,
        String accountName,
        @Nullable String accountBank,
        @Nullable UUID cardId,
        @Nullable String cardLast4
) {
    public static RuleMatchTransactionResponse from(RuleMatchService.MatchedTransaction match, CategoryCatalog catalog) {
        return new RuleMatchTransactionResponse(
                match.id(),
                match.date(),
                match.amount(),
                match.type() != null ? match.type().name() : null,
                match.sourcedDescription(),
                CategoryRef.of(catalog, match.categoryKey()),
                SubcategoryRef.of(catalog, match.subcategoryKey()),
                match.categorySource(),
                match.appliedRuleId(),
                match.appliedRuleName(),
                match.accountId(),
                match.accountName(),
                match.accountBank(),
                match.cardId(),
                match.cardLast4()
        );
    }
}
