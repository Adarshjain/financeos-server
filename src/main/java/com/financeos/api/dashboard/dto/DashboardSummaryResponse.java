package com.financeos.api.dashboard.dto;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/** Lightweight dashboard metadata for list views (no widget detail). */
public record DashboardSummaryResponse(
        UUID id,
        String name,
        @Nullable String description,
        boolean isDefault,
        int widgetCount,
        Instant createdAt,
        Instant updatedAt) {
}
