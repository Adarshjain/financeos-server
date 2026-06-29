package com.financeos.api.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A full dashboard with its widgets enriched with referenced-report metadata. */
public record DashboardResponse(
        UUID id,
        String name,
        String description,
        boolean isDefault,
        List<WidgetResponse> widgets,
        Instant createdAt,
        Instant updatedAt) {
}
