package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.financeos.core.exception.ValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardValidatorTest {

    private final DashboardValidator validator = new DashboardValidator();
    private static final UUID REPORT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static DashboardWidget widget(String id, int x, int y, int w, int h) {
        return new DashboardWidget(id, REPORT, null, new WidgetLayout(x, y, w, h));
    }

    @Test
    void validDashboardPasses() {
        assertDoesNotThrow(() -> validator.validate("My Finances",
                List.of(widget("w1", 0, 0, 6, 4), widget("w2", 6, 0, 6, 4))));
    }

    @Test
    void emptyDashboardPasses() {
        assertDoesNotThrow(() -> validator.validate("Empty", List.of()));
    }

    @Test
    void blankNameFails() {
        assertThrows(ValidationException.class, () -> validator.validate("  ", List.of()));
    }

    @Test
    void duplicateWidgetIdFails() {
        assertThrows(ValidationException.class, () -> validator.validate("D",
                List.of(widget("w1", 0, 0, 4, 2), widget("w1", 4, 0, 4, 2))));
    }

    @Test
    void widgetExceedingGridWidthFails() {
        // x=8, w=6 -> 14 > 12
        assertThrows(ValidationException.class, () -> validator.validate("D",
                List.of(widget("w1", 8, 0, 6, 4))));
    }

    @Test
    void missingLayoutFails() {
        DashboardWidget w = new DashboardWidget("w1", REPORT, null, null);
        assertThrows(ValidationException.class, () -> validator.validate("D", List.of(w)));
    }

    @Test
    void missingReportIdFails() {
        DashboardWidget w = new DashboardWidget("w1", null, null, new WidgetLayout(0, 0, 4, 2));
        assertThrows(ValidationException.class, () -> validator.validate("D", List.of(w)));
    }
}
