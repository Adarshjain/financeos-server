package com.financeos.api.lending.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateCounterpartyRequest(
        @NotBlank String name,
        String notes
) {}
