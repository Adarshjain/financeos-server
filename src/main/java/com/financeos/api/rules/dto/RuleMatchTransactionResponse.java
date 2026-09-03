package com.financeos.api.rules.dto;

import com.financeos.api.category.dto.CategoryResponse;
import com.financeos.domain.categorization.RuleMatchService;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record RuleMatchTransactionResponse(
        UUID id,
        LocalDate date,
        BigDecimal amount,
        String type,
        String sourcedDescription,
        List<CategoryResponse> categories,
        @Nullable String reviewType,
        @Nullable UUID appliedRuleId
) {
    public static RuleMatchTransactionResponse from(RuleMatchService.MatchedTransaction match) {
        List<CategoryResponse> categories = match.categories().stream()
                .map(CategoryResponse::from)
                .sorted(Comparator.comparing(CategoryResponse::name))
                .toList();
        return new RuleMatchTransactionResponse(
                match.id(),
                match.date(),
                match.amount(),
                match.type() != null ? match.type().name() : null,
                match.sourcedDescription(),
                categories,
                match.reviewType() != null ? match.reviewType().name() : null,
                match.appliedRuleId()
        );
    }
}
