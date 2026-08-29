package com.financeos.api.llm.dto;

public record RoutingEntryDto(
        int position,
        String optionId,
        String optionLabel,
        String provider,
        String providerName,
        String model,
        boolean hasKey
) {}
