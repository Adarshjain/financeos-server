package com.financeos.api.dashboard.dto;

import com.financeos.domain.dashboard.WidgetLayout;

import java.util.UUID;

/** A widget enriched with its referenced report's metadata for rendering. */
public record WidgetResponse(
        String id,
        UUID reportId,
        String title,
        WidgetLayout layout,
        ReportRef report) {
}
