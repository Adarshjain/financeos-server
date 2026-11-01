package com.financeos.api.categorize.dto;

import org.springframework.lang.Nullable;

/** The user's rule that matches a description: its category/sub-category, or both null when none does. */
public record CategorizeResponse(
        @Nullable String categoryKey,
        @Nullable String subcategoryKey,
        @Nullable String source
) {
    public static CategorizeResponse empty() {
        return new CategorizeResponse(null, null, null);
    }

    public static CategorizeResponse rule(String categoryKey, @Nullable String subcategoryKey) {
        return new CategorizeResponse(categoryKey, subcategoryKey, "RULE");
    }
}
