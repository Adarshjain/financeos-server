package com.financeos.domain.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.core.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Validates a dashboard's structure: a name is required, widget ids are unique, each widget
 * fits the {@value #GRID_COLUMNS}-column grid, a report widget names a report and a built-in
 * widget names a registered key with valid params and at least the entry's minimum width, and a
 * text widget (section header) has a title, spans the full grid width and carries at most a
 * description.
 * Report references are NOT checked here — they resolve at read time (a deleted/foreign report
 * renders as unavailable), so editing a dashboard is never blocked by an unrelated report having
 * been deleted.
 */
@Component
public class DashboardValidator {

    private static final int GRID_COLUMNS = 100;
    static final int TEXT_TITLE_MAX = 120;
    static final int TEXT_DESCRIPTION_MAX = 300;
    static final String TEXT_DESCRIPTION = "description";

    private final BuiltinWidgetRegistry builtins;

    public DashboardValidator(BuiltinWidgetRegistry builtins) {
        this.builtins = builtins;
    }

    public void validate(String name, List<DashboardWidget> widgets) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Dashboard name is required");
        }
        if (widgets == null) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (DashboardWidget widget : widgets) {
            if (widget.id() == null || widget.id().isBlank()) {
                throw new ValidationException("Each widget requires an id");
            }
            if (!ids.add(widget.id())) {
                throw new ValidationException("Duplicate widget id: " + widget.id());
            }
            WidgetLayout layout = widget.layout();
            if (layout == null) {
                throw new ValidationException("Widget '" + widget.id() + "' requires a layout");
            }
            if (layout.x() < 0 || layout.w() < 1 || layout.x() + layout.w() > GRID_COLUMNS
                    || layout.y() < 0 || layout.h() < 1) {
                throw new ValidationException(
                        "Widget '" + widget.id() + "' has an invalid layout (must fit a "
                                + GRID_COLUMNS + "-column grid)");
            }
            String kind = widget.resolvedKind();
            switch (kind) {
                case DashboardWidget.KIND_REPORT -> validateReportWidget(widget);
                case DashboardWidget.KIND_BUILTIN -> validateBuiltinWidget(widget);
                case DashboardWidget.KIND_TEXT -> validateTextWidget(widget);
                default -> throw new ValidationException(
                        "Widget '" + widget.id() + "' has an unknown kind: " + kind);
            }
        }
    }

    private void validateReportWidget(DashboardWidget widget) {
        if (widget.reportId() == null) {
            throw new ValidationException("Widget '" + widget.id() + "' requires a reportId");
        }
    }

    private void validateBuiltinWidget(DashboardWidget widget) {
        String key = widget.builtinKey();
        if (key == null || key.isBlank()) {
            throw new ValidationException("Widget '" + widget.id() + "' requires a builtinKey");
        }
        BuiltinWidgetRegistry.Entry entry = builtins.find(key)
                .orElseThrow(() -> new ValidationException(
                        "Widget '" + widget.id() + "' references an unknown built-in: " + key));
        builtins.validateParams(entry, widget.params());
        if (widget.layout().w() < entry.minW()) {
            throw new ValidationException("Widget '" + widget.id() + "' must be at least " + entry.minW()
                    + " columns wide for built-in '" + key + "'");
        }
    }

    private void validateTextWidget(DashboardWidget widget) {
        String id = widget.id();
        if (widget.reportId() != null || (widget.builtinKey() != null && !widget.builtinKey().isBlank())) {
            throw new ValidationException("Header '" + id + "' cannot reference a report or built-in");
        }
        String title = widget.title();
        if (title == null || title.isBlank()) {
            throw new ValidationException("Header '" + id + "' requires a title");
        }
        if (title.length() > TEXT_TITLE_MAX) {
            throw new ValidationException("Header '" + id + "' title must be at most " + TEXT_TITLE_MAX + " characters");
        }
        if (widget.layout().x() != 0 || widget.layout().w() != GRID_COLUMNS) {
            throw new ValidationException("Header '" + id + "' must span the full " + GRID_COLUMNS + "-column width");
        }
        JsonNode params = widget.paramsOrNull();
        if (params == null) {
            return;
        }
        if (!params.isObject()) {
            throw new ValidationException("Header '" + id + "' params must be an object");
        }
        Iterator<String> names = params.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!TEXT_DESCRIPTION.equals(name)) {
                throw new ValidationException("Header '" + id + "' has an unknown param: " + name);
            }
        }
        JsonNode description = params.get(TEXT_DESCRIPTION);
        if (description == null || description.isNull()) {
            return;
        }
        if (!description.isTextual()) {
            throw new ValidationException("Header '" + id + "' description must be text");
        }
        if (description.asText().length() > TEXT_DESCRIPTION_MAX) {
            throw new ValidationException(
                    "Header '" + id + "' description must be at most " + TEXT_DESCRIPTION_MAX + " characters");
        }
    }
}
