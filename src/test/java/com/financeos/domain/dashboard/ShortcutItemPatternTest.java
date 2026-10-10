package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.Entry;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A shortcut item can only point inside the app: same-app page paths (no protocol-relative or
 * scheme URLs), action ids, and UUID references to the user's things.
 */
class ShortcutItemPatternTest {

    private static final Pattern PATTERN = Pattern.compile(BuiltinWidgetRegistry.SHORTCUT_ITEM_PATTERN);
    private static final String ID = "1b4e28ba-2fa1-11d2-883f-0016d3cca427";

    private final ObjectMapper mapper = new ObjectMapper();
    private final BuiltinWidgetRegistry registry = new BuiltinWidgetRegistry(mapper);

    @Test
    void sameAppPagesActionsAndUuidReferencesAreAccepted() {
        for (String ok : List.of("page:/", "page:/transactions/review", "page:/transactions?status=pending&x=1",
                "page:/rewards?account=" + ID, "page:/a-b_c.d", "action:add-transaction", "action:ask-chat",
                "account:" + ID, "report:" + ID.toUpperCase(), "dashboard:" + ID)) {
            assertTrue(PATTERN.matcher(ok).matches(), ok);
        }
    }

    @Test
    void offSiteAndScriptTargetsAreRejected() {
        for (String bad : List.of(
                "page://evil.example",
                "page://evil.example/path",
                "page:javascript:alert(1)",
                "page:/javascript:alert(1)",
                "page:/\\\\evil.example",
                "page:/a\\b",
                "page:https://evil.example",
                "page:evil.example",
                "page:/a:b",
                "page:/has space",
                "page:/" + "a".repeat(200))) {
            assertFalse(PATTERN.matcher(bad).matches(), bad);
        }
    }

    @Test
    void actionsAreLowerCaseIdsAndReferencesAreUuids() {
        for (String bad : List.of("action:", "action:Add", "action:add_txn", "action:add transaction",
                "action:<script>", "action:a:b", "account:", "account:123", "account:../x", "report:not-a-uuid",
                "dashboard:" + ID + "x", "link:/x", "page:", "widget:/x")) {
            assertFalse(PATTERN.matcher(bad).matches(), bad);
        }
    }

    @Test
    void theRegistryRejectsAnUnsafeShortcutWhenParamsAreSaved() {
        Entry shortcuts = registry.require("shortcuts");
        ObjectNode bad = mapper.createObjectNode();
        bad.putArray("items").add("page:/upcoming").add("page://evil.example");
        assertThrows(ValidationException.class, () -> registry.validateParams(shortcuts, bad));
        ObjectNode ok = mapper.createObjectNode();
        ok.putArray("items").add("page:/upcoming").add("account:" + ID);
        assertDoesNotThrow(() -> registry.validateParams(shortcuts, ok));
    }
}
