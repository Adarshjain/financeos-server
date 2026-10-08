package com.financeos.api.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.List;

/** A built-in widget the dashboard editor can place: its catalog entry and parameter schema. */
public record BuiltinWidgetResponse(
        String key,
        String label,
        String description,
        int minW,
        /** {@code template} (runs a report definition through the engine) or {@code component}. */
        String kind,
        /** KPI | CHART | TABLE for template built-ins; null for components. */
        @Nullable String templateType,
        /** Report datasource the template runs over; null for components. */
        @Nullable String datasource,
        /** The template definition with default params applied; null for components. */
        @Nullable JsonNode templateDefinition,
        @Nullable String href,
        List<BuiltinParamResponse> params) {
}
