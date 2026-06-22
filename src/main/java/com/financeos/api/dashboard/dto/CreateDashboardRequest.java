package com.financeos.api.dashboard.dto;

import com.financeos.domain.dashboard.DashboardWidget;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Body for creating a dashboard. {@code widgets} may be null/empty (an empty canvas). */
public record CreateDashboardRequest(
        @NotBlank String name,
        String description,
        boolean isDefault,
        List<DashboardWidget> widgets) {
}
