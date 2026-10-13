package com.financeos.api.llm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One row of a group's routing order: either an id from {@code llm.routing-options}, or
 * {@code optionId = "custom"} with a provider and a user-typed model (plus optional call knobs).
 */
public record RoutingEntryRequest(
        @NotBlank String optionId,
        @Nullable String provider,
        @Nullable @Size(max = 255) String model,
        @Nullable String structuredOutput,
        @Nullable String reasoningEffort,
        @Nullable @Min(1) @Max(1_000_000) Integer maxOutputTokens,
        @Nullable @Size(max = 64) String providerPin
) {
    @JsonCreator
    public RoutingEntryRequest(
            @JsonProperty("optionId") String optionId,
            @JsonProperty("provider") String provider,
            @JsonProperty("model") String model,
            @JsonProperty("structuredOutput") String structuredOutput,
            @JsonProperty("reasoningEffort") String reasoningEffort,
            @JsonProperty("maxOutputTokens") Integer maxOutputTokens,
            @JsonProperty("providerPin") String providerPin) {
        this.optionId = optionId;
        this.provider = provider;
        this.model = model;
        this.structuredOutput = structuredOutput;
        this.reasoningEffort = reasoningEffort;
        this.maxOutputTokens = maxOutputTokens;
        this.providerPin = providerPin;
    }

    /** A menu option carries nothing but its id. */
    public RoutingEntryRequest(String optionId) {
        this(optionId, null, null, null, null, null, null);
    }
}
