package com.financeos.api.categorize.dto;

import com.financeos.api.category.dto.CategoryResponse;
import com.financeos.domain.categorization.CategorizationService;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record CategorizeResponse(
        List<CategoryResponse> categories,
        UUID ruleId,
        boolean fromRule
) {
    public static CategorizeResponse from(CategorizationService.SuggestionResult result) {
        List<CategoryResponse> categoryResponses = result.categories().stream()
                .map(CategoryResponse::from)
                .sorted(Comparator.comparing(CategoryResponse::name))
                .toList();
        return new CategorizeResponse(categoryResponses, result.ruleId(), result.fromRule());
    }
}
