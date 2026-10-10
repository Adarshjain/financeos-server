package com.financeos.api.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.List;

/** A built-in widget the dashboard editor can place: its catalog entry and parameter schema. */
public record BuiltinWidgetResponse(
        String key,
        String label,
        /** The long picker text; shown only in the picker. */
        String description,
        /** overview | cards_rewards | spending | investments | loans_lending | shortcuts */
        String category,
        /** The short line under the title on the dashboard; null for none. */
        @Nullable String subtitle,
        /** A "Needs …" sentence for the picker; null when nothing is needed. */
        @Nullable String requires,
        /** The client renderer for a template's data; null for the report type's default rendering. */
        @Nullable String view,
        /** Null when the current user can use the widget, else what to add first (e.g. "Add a credit card first"). */
        @Nullable String unavailableReason,
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
