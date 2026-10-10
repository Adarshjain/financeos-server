package com.financeos.domain.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * One widget on a dashboard plus its grid placement. Stored as part of the dashboard's
 * {@code widgets} JSON array.
 *
 * <p>Three kinds: a {@code report} widget references a saved report by {@code reportId}; a
 * {@code builtin} widget references a {@link BuiltinWidgetRegistry} entry by {@code builtinKey}
 * with optional {@code params}; a {@code text} widget is a full-width section header whose
 * {@code title} is the heading and whose optional {@code params.description} is a line under it.
 * {@code kind} null (widgets stored before built-ins existed) means {@code report}.
 */
public record DashboardWidget(
        @NotNull String id,
        @Nullable UUID reportId,
        @Nullable String title,
        @NotNull WidgetLayout layout,
        @Nullable String kind,
        @Nullable String builtinKey,
        @Nullable JsonNode params) {

    public static final String KIND_REPORT = "report";
    public static final String KIND_BUILTIN = "builtin";
    public static final String KIND_TEXT = "text";

    /** A report widget (the pre-built-in shape). */
    public DashboardWidget(String id, UUID reportId, String title, WidgetLayout layout) {
        this(id, reportId, title, layout, KIND_REPORT, null, null);
    }

    /** {@code kind} with the legacy null resolved to {@value #KIND_REPORT}. Not a bean getter on purpose. */
    public String resolvedKind() {
        return kind == null || kind.isBlank() ? KIND_REPORT : kind;
    }

    /** True for a {@code builtin} widget. Not a bean getter on purpose (records serialize components only). */
    public boolean usesBuiltin() {
        return KIND_BUILTIN.equals(resolvedKind());
    }

    /** True for a {@code text} (section header) widget. Not a bean getter on purpose. */
    public boolean usesText() {
        return KIND_TEXT.equals(resolvedKind());
    }

    /** {@code params} with an explicit JSON null treated as absent. Not a bean getter on purpose. */
    @Nullable
    public JsonNode paramsOrNull() {
        return params == null || params.isNull() ? null : params;
    }
}
