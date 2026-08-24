package com.financeos.api.llm.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateLlmKeyRequest(
        @NotBlank(message = "Provider is required") String provider,
        @NotBlank(message = "API key is required") String key,
        String label
) {}
