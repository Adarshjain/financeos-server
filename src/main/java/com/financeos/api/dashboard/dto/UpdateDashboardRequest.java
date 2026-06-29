package com.financeos.api.dashboard.dto;

import com.financeos.domain.dashboard.DashboardWidget;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Body for updating a dashboard — replaces name, description, isDefault, and the full widget set.
 * Setting {@code isDefault} true makes this the user's default (clearing any previous default).
 */
public record UpdateDashboardRequest(
        @NotBlank String name,
        String description,
        boolean isDefault,
        List<DashboardWidget> widgets) {
}
