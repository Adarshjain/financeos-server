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
        @Nullable String href) {
}
