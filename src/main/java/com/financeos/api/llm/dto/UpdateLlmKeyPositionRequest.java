package com.financeos.api.llm.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateLlmKeyPositionRequest(
        @NotNull(message = "Position is required") Integer position
) {}
