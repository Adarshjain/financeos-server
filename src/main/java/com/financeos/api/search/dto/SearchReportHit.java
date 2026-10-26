package com.financeos.api.search.dto;

import com.financeos.domain.report.ReportType;

import java.util.UUID;

/** A report matched by name. */
public record SearchReportHit(UUID id, String name, ReportType type) {
}
