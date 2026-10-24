package com.financeos.api.report.dto;

import java.util.UUID;

/** A dashboard that shows a report (one entry per dashboard, however many widgets use it). */
public record ReportDashboardRef(UUID id, String name) {
}
