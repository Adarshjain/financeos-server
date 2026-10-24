package com.financeos.api.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportType;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReportSummaryResponse(
        UUID id,
        String name,
        @Nullable String description,
        ReportType type,
        String datasource,
        Instant createdAt,
        Instant updatedAt,
        List<ReportDashboardRef> dashboards,
        @Nullable String chartType
) {
    /** {@code dashboards}: the dashboards that use the report, ordered by name (empty when none). */
    public static ReportSummaryResponse from(Report report, List<ReportDashboardRef> dashboards,
                                             ObjectMapper mapper) {
        return new ReportSummaryResponse(
                report.getId(),
                report.getName(),
                report.getDescription(),
                report.getType(),
                report.getDatasource(),
                report.getCreatedAt(),
                report.getUpdatedAt(),
                dashboards,
                chartType(report, mapper)
        );
    }

    /** The stored definition's chartType wire value for CHART reports; null otherwise or when unreadable. */
    @Nullable
    private static String chartType(Report report, ObjectMapper mapper) {
        if (report.getType() != ReportType.CHART) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(report.getDefinition()).get("chartType");
            if (node == null || !node.isTextual()) {
                return null;
            }
            return com.financeos.domain.report.definition.ChartType.from(node.asText()).json();
        } catch (Exception e) {
            return null;
        }
    }
}
