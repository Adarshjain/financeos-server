package com.financeos.api.dashboard.dto;

import java.time.Instant;
import java.util.UUID;

/** Lightweight dashboard metadata for list views (no widget detail). */
public record DashboardSummaryResponse(
        UUID id,
        String name,
        String description,
        int widgetCount,
        Instant createdAt,
        Instant updatedAt) {
}
