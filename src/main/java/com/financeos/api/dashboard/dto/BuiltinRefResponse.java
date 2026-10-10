package com.financeos.api.dashboard.dto;

import org.springframework.lang.Nullable;

/**
 * Server-resolved metadata for a built-in widget on a dashboard (the registry entry behind
 * {@code builtinKey}). Absent on the widget when the key is no longer registered.
 */
public record BuiltinRefResponse(
        String key,
        String label,
        int minW,
        /** {@code template} (renders a report-engine definition) or {@code component} (client-rendered). */
        String kind,
        /** KPI | CHART | TABLE for template built-ins; null for components. */
        @Nullable String templateType,
        @Nullable String href,
        /** overview | cards_rewards | spending | investments | loans_lending | shortcuts */
        String category,
        /** The short line under the title on the dashboard; null for none. */
        @Nullable String subtitle,
        /** The client renderer for a template's data; null for the report type's default rendering. */
        @Nullable String view) {
}
