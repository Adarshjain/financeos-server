package com.financeos.api.report.dto;

import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportType;

import java.time.Instant;
import java.util.UUID;

public record ReportSummaryResponse(
        UUID id,
        String name,
        String description,
        ReportType type,
        String datasource,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReportSummaryResponse from(Report report) {
        return new ReportSummaryResponse(
                report.getId(),
                report.getName(),
                report.getDescription(),
                report.getType(),
                report.getDatasource(),
                report.getCreatedAt(),
                report.getUpdatedAt()
        );
    }
}
