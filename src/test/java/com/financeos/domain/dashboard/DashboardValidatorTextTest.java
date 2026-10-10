package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link DashboardValidator} rules for {@code text} widgets (full-width section headers). */
class DashboardValidatorTextTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DashboardValidator validator = new DashboardValidator(new BuiltinWidgetRegistry(mapper));

    private static DashboardWidget header(String title, int x, int w, JsonNode params) {
        return new DashboardWidget("h1", null, title, new WidgetLayout(x, 0, w, 4),
                DashboardWidget.KIND_TEXT, null, params);
    }

    private ObjectNode description(String text) {
        return mapper.createObjectNode().put("description", text);
    }

    private String messageOf(DashboardWidget widget) {
        return assertThrows(ValidationException.class, () -> validator.validate("D", List.of(widget))).getMessage();
    }

    @Test
    void titleOnlyHeaderPasses() {
        assertDoesNotThrow(() -> validator.validate("D", List.of(header("Spending", 0, 100, null))));
    }

    @Test
    void headerWithDescriptionPasses() {
        assertDoesNotThrow(() -> validator.validate("D",
                List.of(header("Spending", 0, 100, description("Cards and bank accounts")))));
    }

    @Test
    void explicitNullParamsAndNullDescriptionPass() {
        ObjectNode nullDescription = mapper.createObjectNode().putNull("description");
        assertDoesNotThrow(() -> validator.validate("D", List.of(header("A", 0, 100, mapper.nullNode()))));
        assertDoesNotThrow(() -> validator.validate("D", List.of(header("A", 0, 100, nullDescription))));
    }

    @Test
    void headerAmongReportAndBuiltinWidgetsPasses() {
        DashboardWidget report = new DashboardWidget("r1", UUID.randomUUID(), null, new WidgetLayout(0, 4, 50, 10));
        DashboardWidget builtin = new DashboardWidget("b1", null, null, new WidgetLayout(50, 4, 50, 16),
                DashboardWidget.KIND_BUILTIN, "net_worth", null);
        assertDoesNotThrow(() -> validator.validate("D", List.of(header("Overview", 0, 100, null), report, builtin)));
    }

    @Test
    void missingOrBlankTitleFails() {
        assertTrue(messageOf(header(null, 0, 100, null)).contains("requires a title"));
        assertTrue(messageOf(header("   ", 0, 100, null)).contains("requires a title"));
    }

    @Test
    void titleLengthLimit() {
        assertDoesNotThrow(() -> validator.validate("D",
                List.of(header("a".repeat(DashboardValidator.TEXT_TITLE_MAX), 0, 100, null))));
        assertTrue(messageOf(header("a".repeat(DashboardValidator.TEXT_TITLE_MAX + 1), 0, 100, null))
                .contains("at most " + DashboardValidator.TEXT_TITLE_MAX));
    }

    @Test
    void headerNarrowerThanTheGridFails() {
        assertTrue(messageOf(header("A", 0, 50, null)).contains("full 100-column width"));
    }

    @Test
    void headerNotStartingAtColumnZeroFails() {
        // x=1 with w=99 still ends at column 100 but does not span the full width.
        assertTrue(messageOf(header("A", 1, 99, null)).contains("full 100-column width"));
    }

    @Test
    void headerReferencingAReportOrBuiltinFails() {
        DashboardWidget withReport = new DashboardWidget("h1", UUID.randomUUID(), "A", new WidgetLayout(0, 0, 100, 4),
                DashboardWidget.KIND_TEXT, null, null);
        DashboardWidget withBuiltin = new DashboardWidget("h1", null, "A", new WidgetLayout(0, 0, 100, 4),
                DashboardWidget.KIND_TEXT, "net_worth", null);
        assertTrue(messageOf(withReport).contains("cannot reference"));
        assertTrue(messageOf(withBuiltin).contains("cannot reference"));
    }

    @Test
    void nonObjectParamsFail() {
        assertTrue(messageOf(header("A", 0, 100, mapper.createArrayNode())).contains("must be an object"));
    }

    @Test
    void unknownParamFails() {
        ObjectNode params = description("ok").put("color", "red");
        assertTrue(messageOf(header("A", 0, 100, params)).contains("unknown param: color"));
    }

    @Test
    void nonTextDescriptionFails() {
        ObjectNode params = mapper.createObjectNode().put("description", 5);
        assertTrue(messageOf(header("A", 0, 100, params)).contains("description must be text"));
    }

    @Test
    void descriptionLengthLimit() {
        assertDoesNotThrow(() -> validator.validate("D",
                List.of(header("A", 0, 100, description("d".repeat(DashboardValidator.TEXT_DESCRIPTION_MAX))))));
        assertTrue(messageOf(header("A", 0, 100, description("d".repeat(DashboardValidator.TEXT_DESCRIPTION_MAX + 1))))
                .contains("at most " + DashboardValidator.TEXT_DESCRIPTION_MAX));
    }
}
