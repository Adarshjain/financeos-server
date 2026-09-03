package com.financeos.domain.dashboard;

import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * One widget on a dashboard: a reference to a saved report plus its grid placement.
 * Stored as part of the dashboard's {@code widgets} JSON array.
 */
public record DashboardWidget(@NotNull String id, @NotNull UUID reportId, @Nullable String title, @NotNull WidgetLayout layout) {
}
