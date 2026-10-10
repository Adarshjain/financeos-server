package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.Entry;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.ParamSpec;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The catalog fields added for the widget picker (category, subtitle, requires, view,
 * availability, long descriptions), the new param types (enum, string_list, uuid refs) and the
 * quarter width.
 */
class BuiltinWidgetRegistryCatalogFieldsTest {

    private static final String SHORTCUT_PATTERN = "^(page|action|account|report|dashboard):[A-Za-z0-9/_\\-.:?=&]{1,200}$";

    private final ObjectMapper mapper = new ObjectMapper();
    private final BuiltinWidgetRegistry registry = new BuiltinWidgetRegistry(mapper);

    private static Entry component(List<ParamSpec> params) {
        return new Entry("custom", "Custom", "d", 25, BuiltinWidgetRegistry.KIND_COMPONENT, null, null, null, null,
                params, null);
    }

    private ObjectNode params() {
        return mapper.createObjectNode();
    }

    private void rejects(Entry entry, JsonNode params, String messagePart) {
        ValidationException e = assertThrows(ValidationException.class, () -> registry.validateParams(entry, params));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    // ------------------------------------------------------------------ the four entries

    @Test
    void theFourEntriesCarryTheirCategoryAndSubtitle() {
        assertEquals(BuiltinWidgetRegistry.CATEGORY_OVERVIEW, registry.require("net_worth").category());
        assertEquals(BuiltinWidgetRegistry.CATEGORY_OVERVIEW, registry.require("attention").category());
        assertEquals(BuiltinWidgetRegistry.CATEGORY_OVERVIEW, registry.require("upcoming").category());
        assertEquals(BuiltinWidgetRegistry.CATEGORY_CARDS_REWARDS, registry.require("bills_due").category());

        assertEquals("Assets minus liabilities", registry.require("net_worth").subtitle());
        assertEquals("What needs you now", registry.require("attention").subtitle());
        assertEquals("Due in the next few days", registry.require("upcoming").subtitle());
        assertEquals("Card bills and due dates", registry.require("bills_due").subtitle());
    }

    @Test
    void theFourEntriesNeedNothingHaveNoViewAndAreAlwaysAvailable() {
        for (Entry e : registry.all().subList(0, 4)) {
            assertNull(e.requires(), e.key());
            assertNull(e.view(), e.key());
            assertSame(BuiltinAvailability.ALWAYS, e.availability(), e.key());
            assertTrue(BuiltinWidgetRegistry.CATEGORIES.contains(e.category()), e.key());
        }
    }

    @Test
    void descriptionsAreTheLongPickerTexts() {
        assertEquals("Everything you own minus everything you owe, across bank accounts, cards, investments, loans "
                + "and money lent. Tap the number to see how each account adds up.", registry.require("net_worth").description());
        assertEquals("The few things that need you now — bills, EMIs, missing statements, Gmail reconnects and "
                + "transactions waiting for review — with the action right on the row.", registry.require("attention").description());
        assertEquals("Bills, EMIs, expected statements and lending returns due in the next few days, soonest first.",
                registry.require("upcoming").description());
        assertEquals("Each card's bill as it moves from unbilled spend to statement to paid, with Mark paid on the card. "
                + "Add one per card or one for all.", registry.require("bills_due").description());
    }

    @Test
    void billsDueAccountIdPointsAtACreditCard() {
        ParamSpec accountId = registry.require("bills_due").params().get(0);
        assertEquals("accountId", accountId.name());
        assertEquals(BuiltinWidgetRegistry.PARAM_UUID, accountId.type());
        assertEquals(BuiltinWidgetRegistry.REF_CREDIT_CARD, accountId.ref());
        assertNull(accountId.options());
        assertNull(accountId.maxItems());
        assertNull(accountId.itemPattern());
    }

    @Test
    void upcomingDaysIsAnIntWithoutARef() {
        ParamSpec days = registry.require("upcoming").params().get(0);
        assertEquals(BuiltinWidgetRegistry.PARAM_INT, days.type());
        assertNull(days.ref());
        assertEquals(1, days.min());
        assertEquals(90, days.max());
    }

    @Test
    void theLegacyEntryConstructorDefaultsToAnAlwaysAvailableOverviewEntry() {
        Entry e = component(List.of());
        assertEquals(BuiltinWidgetRegistry.CATEGORY_OVERVIEW, e.category());
        assertNull(e.subtitle());
        assertNull(e.requires());
        assertNull(e.view());
        assertSame(BuiltinAvailability.ALWAYS, e.availability());
    }

    // ------------------------------------------------------------------ param factories

    @Test
    void paramFactoriesFillOnlyTheirOwnFields() {
        ParamSpec n = ParamSpec.intParam("n", false, mapper.getNodeFactory().numberNode(5), 3, 10);
        assertEquals(List.of("int", 3, 10, 5), List.of(n.type(), n.min(), n.max(), n.defaultValue().asInt()));
        assertNull(n.ref());

        ParamSpec loan = ParamSpec.uuidRef("loanId", BuiltinWidgetRegistry.REF_LOAN, true);
        assertEquals("uuid", loan.type());
        assertEquals("loan", loan.ref());
        assertTrue(loan.required());

        ParamSpec mode = ParamSpec.enumParam("mode", false, mapper.getNodeFactory().textNode("a"), List.of("a", "b"));
        assertEquals("enum", mode.type());
        assertEquals(List.of("a", "b"), mode.options());
        assertNull(mode.min());

        ParamSpec items = ParamSpec.stringList("items", false, null, 12, SHORTCUT_PATTERN);
        assertEquals("string_list", items.type());
        assertEquals(12, items.maxItems());
        assertEquals(SHORTCUT_PATTERN, items.itemPattern());
        assertNull(items.options());
    }

    @Test
    void theLegacyParamConstructorHasNoRefOptionsOrListLimits() {
        ParamSpec p = new ParamSpec("accountId", BuiltinWidgetRegistry.PARAM_UUID, false, null, null, null);
        assertNull(p.ref());
        assertNull(p.options());
        assertNull(p.maxItems());
        assertNull(p.itemPattern());
    }

    // ------------------------------------------------------------------ uuid refs

    @Test
    void aUuidRefIsValidatedLikeAnyUuid() {
        Entry entry = component(List.of(ParamSpec.uuidRef("accountId", BuiltinWidgetRegistry.REF_ACCOUNT, true)));
        assertDoesNotThrow(() -> registry.validateParams(entry, params().put("accountId", "1b4e28ba-2fa1-11d2-883f-0016d3cca427")));
        rejects(entry, params().put("accountId", "nope"), "must be a uuid string");
        rejects(entry, params(), "requires param 'accountId'");
    }

    // ------------------------------------------------------------------ enum

    @Test
    void enumAcceptsOnlyItsOptions() {
        Entry entry = component(List.of(ParamSpec.enumParam("mode", false, null, List.of("week", "month"))));
        assertDoesNotThrow(() -> registry.validateParams(entry, params().put("mode", "week")));
        assertDoesNotThrow(() -> registry.validateParams(entry, params()));
        rejects(entry, params().put("mode", "year"), "must be one of [week, month]");
        rejects(entry, params().put("mode", 1), "must be one of");
        rejects(entry, params().put("mode", "WEEK"), "must be one of");
    }

    @Test
    void enumWithoutOptionsRejectsEverything() {
        Entry entry = component(List.of(new ParamSpec("mode", BuiltinWidgetRegistry.PARAM_ENUM, false, null, null, null,
                null, null, null, null)));
        rejects(entry, params().put("mode", "x"), "must be one of []");
    }

    // ------------------------------------------------------------------ string_list

    private Entry shortcuts(Integer maxItems, String pattern) {
        return component(List.of(new ParamSpec("items", BuiltinWidgetRegistry.PARAM_STRING_LIST, false, null, null,
                null, null, null, maxItems, pattern)));
    }

    private ObjectNode items(Object... values) {
        ObjectNode p = params();
        ArrayNode array = p.putArray("items");
        for (Object v : values) {
            if (v instanceof Integer i) {
                array.add(i);
            } else {
                array.add((String) v);
            }
        }
        return p;
    }

    @Test
    void stringListAcceptsMatchingItemsInOrderUpToMaxItems() {
        Entry entry = shortcuts(3, SHORTCUT_PATTERN);
        assertDoesNotThrow(() -> registry.validateParams(entry,
                items("action:add-transaction", "page:/transactions/review", "account:1b4e28ba-2fa1-11d2-883f-0016d3cca427")));
        assertDoesNotThrow(() -> registry.validateParams(entry, items()));
        assertDoesNotThrow(() -> registry.validateParams(entry, items("page:/transactions?status=pending&x=1")));
    }

    @Test
    void stringListRejectsMoreThanMaxItems() {
        rejects(shortcuts(2, SHORTCUT_PATTERN), items("page:/a", "page:/b", "page:/c"), "at most 2 items");
    }

    @Test
    void stringListRejectsANonArray() {
        rejects(shortcuts(2, SHORTCUT_PATTERN), params().put("items", "page:/a"), "must be a list of strings");
    }

    @Test
    void stringListRejectsANonStringItem() {
        rejects(shortcuts(5, SHORTCUT_PATTERN), items("page:/a", 7), "must be a list of strings");
    }

    @Test
    void stringListRejectsAnItemThatDoesNotMatchThePattern() {
        Entry entry = shortcuts(5, SHORTCUT_PATTERN);
        rejects(entry, items("page:/a", "widget:/x"), "invalid item: widget:/x");
        rejects(entry, items("page:"), "invalid item");
        rejects(entry, items("page:/a b"), "invalid item");
        rejects(entry, items("page:/" + "a".repeat(200)), "invalid item");
    }

    @Test
    void stringListWithoutLimitsAcceptsAnyStrings() {
        assertDoesNotThrow(() -> registry.validateParams(shortcuts(null, null), items("anything", "goes here")));
    }

    // ------------------------------------------------------------------ quarter width

    @Test
    void aQuarterWidthWidgetPassesWhenItsMinWIs25AndFailsBelow() {
        BuiltinWidgetRegistry stub = mock(BuiltinWidgetRegistry.class);
        when(stub.find("tile")).thenReturn(Optional.of(new Entry("tile", "Tile", "d", 25,
                BuiltinWidgetRegistry.KIND_COMPONENT, null, null, null, null, List.of(), null)));
        DashboardValidator validator = new DashboardValidator(stub);

        assertDoesNotThrow(() -> validator.validate("D", List.of(new DashboardWidget("t", null, null,
                new WidgetLayout(75, 0, 25, 10), DashboardWidget.KIND_BUILTIN, "tile", null))));
        ValidationException e = assertThrows(ValidationException.class, () -> validator.validate("D", List.of(
                new DashboardWidget("t", null, null, new WidgetLayout(0, 0, 24, 10), DashboardWidget.KIND_BUILTIN, "tile", null))));
        assertTrue(e.getMessage().contains("at least 25 columns"), e.getMessage());
    }
}
