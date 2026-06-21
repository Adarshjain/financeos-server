package com.financeos.api.dashboard.dto;

import com.financeos.domain.dashboard.DashboardWidget;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** Body for updating a dashboard — replaces name, description, and the full widget set. */
public record UpdateDashboardRequest(
        @NotBlank String name,
        String description,
        List<DashboardWidget> widgets) {
}
