package com.financeos.api.llm.dto;

import org.springframework.lang.Nullable;

public record RoutingEntryDto(
        int position,
        String optionId,
        String optionLabel,
        String provider,
        String providerName,
        @Nullable String model,
        boolean hasKey
) {}
