package com.financeos.api.categorize.dto;

import org.springframework.lang.Nullable;

/**
 * The suggestion for a description: its category/sub-category and where it came from (RULE = one of the user's
 * rules, AUTO = the built-in engine), or all null when nothing matches.
 */
public record CategorizeResponse(
        @Nullable String categoryKey,
        @Nullable String subcategoryKey,
        @Nullable String source
) {
    public static CategorizeResponse empty() {
        return new CategorizeResponse(null, null, null);
    }

    public static CategorizeResponse of(String categoryKey, @Nullable String subcategoryKey, String source) {
        return new CategorizeResponse(categoryKey, subcategoryKey, source);
    }
}
