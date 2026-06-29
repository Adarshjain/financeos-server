package com.financeos.api.dashboard.dto;

import com.financeos.domain.report.ReportType;

/**
 * Server-resolved metadata for a widget's referenced report. When the report no longer
 * exists or is not owned by the current user, {@code available} is false and name/type are null.
 */
public record ReportRef(String name, ReportType type, boolean available) {
}
