package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.financeos.core.exception.ValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Widget-kind rules of {@link DashboardValidator}: report vs built-in widgets. */
class DashboardValidatorBuiltinTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DashboardValidator validator = new DashboardValidator(new BuiltinWidgetRegistry(mapper));
    private static final UUID REPORT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static DashboardWidget builtin(String id, String key, int x, int w, JsonNode params) {
        return new DashboardWidget(id, null, null, new WidgetLayout(x, 0, w, 10), DashboardWidget.KIND_BUILTIN, key, params);
    }

    private void fails(DashboardWidget widget, String messagePart) {
        ValidationException e = assertThrows(ValidationException.class, () -> validator.validate("D", List.of(widget)));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    private void passes(DashboardWidget... widgets) {
        assertDoesNotThrow(() -> validator.validate("D", List.of(widgets)));
    }

    @Test
    void nullWidgetListPasses() {
        assertDoesNotThrow(() -> validator.validate("D", null));
    }

    @Test
    void explicitReportKindRequiresReportId() {
        passes(new DashboardWidget("r", REPORT, null, new WidgetLayout(0, 0, 10, 4), "report", null, null));
        fails(new DashboardWidget("r", null, null, new WidgetLayout(0, 0, 10, 4), "report", null, null), "requires a reportId");
    }

    @Test
    void legacyNullKindIsValidatedAsAReportWidget() {
        passes(new DashboardWidget("r", REPORT, null, new WidgetLayout(0, 0, 10, 4), null, null, null));
        fails(new DashboardWidget("r", null, null, new WidgetLayout(0, 0, 10, 4), null, "net_worth", null), "requires a reportId");
    }

    @Test
    void unknownKindFails() {
        fails(new DashboardWidget("x", REPORT, null, new WidgetLayout(0, 0, 10, 4), "chart", null, null), "unknown kind");
    }

    @Test
    void builtinRequiresABuiltinKey() {
        fails(builtin("b", null, 0, 100, null), "requires a builtinKey");
        fails(builtin("b", "  ", 0, 100, null), "requires a builtinKey");
    }

    @Test
    void builtinWithUnknownKeyFails() {
        fails(builtin("b", "cash_flow", 0, 100, null), "unknown built-in");
    }

    @Test
    void builtinNeedsNoReportId() {
        passes(builtin("nw", "net_worth", 0, 50, null));
    }

    @Test
    void everyBuiltinPassesAtItsMinimumWidth() {
        passes(builtin("nw", "net_worth", 0, 50, null), builtin("at", "attention", 50, 50, null));
        passes(builtin("up", "upcoming", 0, 100, null));
        passes(builtin("bd", "bills_due", 0, 100, null));
    }

    @Test
    void builtinNarrowerThanItsMinimumWidthFails() {
        fails(builtin("nw", "net_worth", 0, 49, null), "at least 50");
        fails(builtin("at", "attention", 0, 49, null), "at least 50");
        fails(builtin("up", "upcoming", 0, 99, null), "at least 100");
        fails(builtin("bd", "bills_due", 0, 99, null), "at least 100");
    }

    @Test
    void builtinLayoutMustStillFitTheGrid() {
        fails(builtin("nw", "net_worth", 60, 50, null), "invalid layout");
    }

    @Test
    void builtinParamsAreCheckedAgainstTheEntrySchema() {
        passes(builtin("up", "upcoming", 0, 100, mapper.createObjectNode().put("days", 30)));
        passes(builtin("up", "upcoming", 0, 100, NullNode.getInstance()));
        fails(builtin("up", "upcoming", 0, 100, mapper.createObjectNode().put("days", 0)), "between 1 and 90");
        fails(builtin("up", "upcoming", 0, 100, mapper.createObjectNode().put("days", 91)), "between 1 and 90");
        fails(builtin("up", "upcoming", 0, 100, mapper.createObjectNode().put("days", "14")), "must be an integer");
        fails(builtin("up", "upcoming", 0, 100, mapper.createObjectNode().put("months", 1)), "does not accept param");
        fails(builtin("nw", "net_worth", 0, 50, mapper.createObjectNode().put("days", 1)), "does not accept param");
        fails(builtin("up", "upcoming", 0, 100, mapper.createArrayNode()), "must be a JSON object");
    }

    @Test
    void billsDueAccountIdMustBeAUuid() {
        passes(builtin("bd", "bills_due", 0, 100,
                mapper.createObjectNode().put("accountId", UUID.randomUUID().toString())));
        fails(builtin("bd", "bills_due", 0, 100, mapper.createObjectNode().put("accountId", "abc")), "uuid");
        fails(builtin("bd", "bills_due", 0, 100, mapper.createObjectNode().put("accountId", 5)), "uuid");
    }

    @Test
    void widgetIdsMustBeUniqueAcrossKinds() {
        DashboardWidget report = new DashboardWidget("same", REPORT, null, new WidgetLayout(0, 20, 10, 4));
        ValidationException e = assertThrows(ValidationException.class, () -> validator.validate("D",
                List.of(builtin("same", "net_worth", 0, 50, null), report)));
        assertTrue(e.getMessage().contains("Duplicate widget id"));
    }

    @Test
    void mixedValidDashboardPasses() {
        passes(builtin("net_worth", "net_worth", 0, 50, null),
                builtin("attention", "attention", 50, 50, null),
                builtin("upcoming", "upcoming", 0, 100, mapper.createObjectNode().put("days", 14)),
                new DashboardWidget("spend", REPORT, "Spend", new WidgetLayout(0, 60, 100, 28)));
    }
}
