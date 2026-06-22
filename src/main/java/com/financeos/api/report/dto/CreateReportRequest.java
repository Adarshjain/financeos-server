package com.financeos.api.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.ReportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateReportRequest(
        @NotBlank String name,
        String description,
        @NotNull ReportType type,
        @NotNull String datasource,
        @NotNull JsonNode definition
) {
}
