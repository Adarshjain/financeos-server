package com.financeos.api.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateReportRequest(
        @NotBlank String name,
        String description,
        @NotNull JsonNode definition
) {
}
