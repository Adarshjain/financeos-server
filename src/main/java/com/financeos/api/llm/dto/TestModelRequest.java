package com.financeos.api.llm.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Probe a user-typed model with the user's own key before it is allowed onto a routing row. */
public record TestModelRequest(
        @NotBlank String provider,
        @NotBlank @Size(max = 255) String model,
        @Nullable String structuredOutput,
        @Nullable String reasoningEffort,
        @Nullable @Min(1) @Max(1_000_000) Integer maxOutputTokens,
        @Nullable @Size(max = 64) String providerPin
) {}
