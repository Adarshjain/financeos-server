package com.financeos.api.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportType;

import java.time.Instant;
import java.util.UUID;

public record ReportResponse(
        UUID id,
        String name,
        String description,
        ReportType type,
        String datasource,
        JsonNode definition,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReportResponse from(Report report, ObjectMapper mapper) {
        JsonNode definitionNode;
        try {
            definitionNode = mapper.readTree(report.getDefinition());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to parse stored report definition for report " + report.getId(), e);
        }
        return new ReportResponse(
                report.getId(),
                report.getName(),
                report.getDescription(),
                report.getType(),
                report.getDatasource(),
                definitionNode,
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }
}
