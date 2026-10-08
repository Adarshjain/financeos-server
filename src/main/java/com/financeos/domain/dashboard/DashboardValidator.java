package com.financeos.domain.dashboard;

import com.financeos.core.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a dashboard's structure: a name is required, widget ids are unique, each widget
 * fits the {@value #GRID_COLUMNS}-column grid, a report widget names a report and a built-in
 * widget names a registered key with valid params and at least the entry's minimum width.
 * Report references are NOT checked here — they resolve at read time (a deleted/foreign report
 * renders as unavailable), so editing a dashboard is never blocked by an unrelated report having
 * been deleted.
 */
@Component
public class DashboardValidator {

    private static final int GRID_COLUMNS = 100;

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
}
