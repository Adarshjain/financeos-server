package com.financeos.api.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.report.ReportType;
import jakarta.validation.constraints.NotNull;

/** Body for running an ad-hoc (unsaved) report definition. */
public record RunReportRequest(
        @NotNull ReportType type,
        @NotNull String datasource,
        @NotNull JsonNode definition) {
}
