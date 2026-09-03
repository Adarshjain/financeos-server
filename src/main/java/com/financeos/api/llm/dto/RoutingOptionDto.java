package com.financeos.api.llm.dto;

import org.springframework.lang.Nullable;

/**
 * One entry of the fixed, user-pickable routing menu (see {@code llm.routing-options}).
 * {@code available} is false when the user holds no active key for this option's provider —
 * the option still renders, greyed, so the reason it cannot be picked is visible.
 */
public record RoutingOptionDto(
        String id,
        String label,
        String provider,
        String providerName,
        @Nullable String model,
        @Nullable String notes,
        boolean free,
        String trainsOnData,
        boolean available
) {}
