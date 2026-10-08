package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardWidgetTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final WidgetLayout LAYOUT = new WidgetLayout(0, 0, 50, 10);
    private static final UUID REPORT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private List<DashboardWidget> parse(String json) throws Exception {
        return mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(List.class, DashboardWidget.class));
    }

    @Test
    void legacyJsonWithoutKindParsesAsAReportWidget() throws Exception {
        List<DashboardWidget> widgets = parse("[{\"id\":\"w1\",\"reportId\":\"" + REPORT
                + "\",\"title\":\"Spend\",\"layout\":{\"x\":0,\"y\":0,\"w\":50,\"h\":10}}]");
        DashboardWidget w = widgets.get(0);
        assertNull(w.kind());
        assertNull(w.builtinKey());
        assertNull(w.params());
        assertEquals(REPORT, w.reportId());
        assertEquals(DashboardWidget.KIND_REPORT, w.resolvedKind());
        assertFalse(w.usesBuiltin());
    }

    @Test
    void builtinJsonParsesKeyAndParams() throws Exception {
        DashboardWidget w = parse("[{\"id\":\"u\",\"layout\":{\"x\":0,\"y\":0,\"w\":100,\"h\":20},"
                + "\"kind\":\"builtin\",\"builtinKey\":\"upcoming\",\"params\":{\"days\":30}}]").get(0);
        assertEquals("builtin", w.resolvedKind());
        assertTrue(w.usesBuiltin());
        assertEquals("upcoming", w.builtinKey());
        assertEquals(30, w.params().get("days").asInt());
        assertNull(w.reportId());
    }

    @Test
    void blankKindResolvesToReport() {
        DashboardWidget w = new DashboardWidget("w", REPORT, null, LAYOUT, "  ", null, null);
        assertEquals(DashboardWidget.KIND_REPORT, w.resolvedKind());
        assertFalse(w.usesBuiltin());
    }

    @Test
    void unknownKindIsKeptAsIsAndIsNotBuiltin() {
        DashboardWidget w = new DashboardWidget("w", REPORT, null, LAYOUT, "chart", null, null);
        assertEquals("chart", w.resolvedKind());
        assertFalse(w.usesBuiltin());
    }

    @Test
    void legacyConstructorBuildsAnExplicitReportWidget() {
        DashboardWidget w = new DashboardWidget("w", REPORT, "T", LAYOUT);
        assertEquals(DashboardWidget.KIND_REPORT, w.kind());
        assertNull(w.builtinKey());
        assertNull(w.params());
    }

    @Test
    void paramsOrNullTreatsJsonNullAsAbsent() {
        assertNull(new DashboardWidget("w", null, null, LAYOUT, "builtin", "net_worth", null).paramsOrNull());
        assertNull(new DashboardWidget("w", null, null, LAYOUT, "builtin", "net_worth", NullNode.getInstance())
                .paramsOrNull());
        JsonNode params = mapper.createObjectNode().put("days", 7);
        assertEquals(params, new DashboardWidget("w", null, null, LAYOUT, "builtin", "upcoming", params).paramsOrNull());
    }

    @Test
    void serializesOnlyTheRecordComponents() throws Exception {
        DashboardWidget w = new DashboardWidget("u", null, null, LAYOUT, "builtin", "upcoming",
                mapper.createObjectNode().put("days", 14));
        JsonNode json = mapper.readTree(mapper.writeValueAsString(w));
        List<String> names = new ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("id", "reportId", "title", "layout", "kind", "builtinKey", "params"), names);

        DashboardWidget back = mapper.treeToValue(json, DashboardWidget.class);
        assertEquals(w, back);
    }
}
