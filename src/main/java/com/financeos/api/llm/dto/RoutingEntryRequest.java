package com.financeos.api.llm.dto;

import jakarta.validation.constraints.NotBlank;

/** A user's routing choice is one id from {@code llm.routing-options} — never a free-form model string. */
public record RoutingEntryRequest(
        @NotBlank String optionId
) {}
