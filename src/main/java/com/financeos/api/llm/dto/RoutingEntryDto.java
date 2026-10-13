package com.financeos.api.llm.dto;

import jakarta.annotation.Nullable;

public record RoutingEntryDto(
        int position,
        String optionId,
        String optionLabel,
        String provider,
        String providerName,
        @Nullable String model,
        boolean hasKey,
        /** True for a user-typed model row ({@code optionId = "custom"}); the knobs below apply only then. */
        boolean custom,
        @Nullable String structuredOutput,
        @Nullable String reasoningEffort,
        @Nullable Integer maxOutputTokens,
        @Nullable String providerPin
) {
    public RoutingEntryDto(int position, String optionId, String optionLabel, String provider, String providerName,
                           String model, boolean hasKey) {
        this(position, optionId, optionLabel, provider, providerName, model, hasKey, false, null, null, null, null);
    }
}
