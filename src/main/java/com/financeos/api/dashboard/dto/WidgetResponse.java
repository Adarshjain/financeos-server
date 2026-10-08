package com.financeos.api.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.domain.dashboard.WidgetLayout;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * A widget enriched for rendering. {@code kind} is always resolved ({@code report} or
 * {@code builtin}): a report widget carries {@code reportId} + {@code report}; a built-in widget
 * carries {@code builtinKey} + {@code params} + {@code builtin} (null when the key is no longer
 * registered).
 */
public record WidgetResponse(
        String id,
        @Nullable UUID reportId,
        @Nullable String title,
        WidgetLayout layout,
        @Nullable ReportRef report,
        String kind,
        @Nullable String builtinKey,
        @Nullable JsonNode params,
        @Nullable BuiltinRefResponse builtin) {
}
