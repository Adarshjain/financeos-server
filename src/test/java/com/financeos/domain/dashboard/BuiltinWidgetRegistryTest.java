package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.Entry;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.ParamSpec;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.obligations.ObligationsService;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.datasource.impl.ObligationsDatasource;
import com.financeos.domain.report.definition.ReportDefinitions;
import java.util.List;
import org.junit.jupiter.api.Test;

class BuiltinWidgetRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BuiltinWidgetRegistry registry = new BuiltinWidgetRegistry(mapper);

    private ObjectNode params() {
        return mapper.createObjectNode();
    }

    private static int horizonDays(JsonNode definition) {
        for (JsonNode filter : definition.get("filters")) {
            if ("dueDate".equals(filter.path("field").asText())
                    && "next_x_days".equals(filter.path("operator").asText())) {
                return filter.path("value").path("amount").asInt();
            }
        }
        throw new AssertionError("no dueDate next_x_days filter in " + definition);
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void allListsTheFourEntriesInCatalogOrder() {
        assertEquals(List.of("net_worth", "attention", "upcoming", "bills_due"),
                registry.all().stream().map(Entry::key).toList());
    }

    @Test
    void allIsAnUnmodifiableSnapshot() {
        assertThrows(UnsupportedOperationException.class, () -> registry.all().clear());
        assertEquals(4, registry.all().size());
    }

    @Test
    void netWorthIsAKpiTemplateOverNetWorth() {
        Entry e = registry.require(BuiltinWidgetRegistry.NET_WORTH);
        assertEquals("Net worth", e.label());
        assertEquals(BuiltinWidgetRegistry.KIND_TEMPLATE, e.kind());
        assertTrue(e.isTemplate());
        assertEquals(ReportType.KPI, e.templateType());
        assertEquals("net_worth", e.datasource());
        assertEquals(50, e.minW());
        assertEquals("/accounts", e.href());
        assertTrue(e.params().isEmpty());
        assertNull(e.paramApplier());
        JsonNode def = e.templateDefinition();
        assertEquals("signedValue", def.get("measure").asText());
        assertEquals("sum", def.get("aggregation").asText());
        assertTrue(def.get("filters").isArray());
        assertEquals(0, def.get("filters").size());
    }

    @Test
    void attentionIsTheInboxComponent() {
        Entry e = registry.require(BuiltinWidgetRegistry.ATTENTION);
        assertEquals("Inbox", e.label());
        assertEquals(BuiltinWidgetRegistry.KIND_COMPONENT, e.kind());
        assertFalse(e.isTemplate());
        assertEquals(50, e.minW());
        assertEquals("/inbox", e.href());
        assertNull(e.templateType());
        assertNull(e.datasource());
        assertNull(e.templateDefinition());
        assertNull(e.paramApplier());
        assertTrue(e.params().isEmpty());
    }

    @Test
    void upcomingIsATableTemplateOverObligations() {
        Entry e = registry.require(BuiltinWidgetRegistry.UPCOMING);
        assertEquals("Upcoming", e.label());
        assertEquals(BuiltinWidgetRegistry.KIND_TEMPLATE, e.kind());
        assertEquals(ReportType.TABLE, e.templateType());
        assertEquals("obligations", e.datasource());
        assertEquals(100, e.minW());
        assertEquals("/upcoming", e.href());
        assertNotNull(e.paramApplier());

        JsonNode def = e.templateDefinition();
        assertEquals("raw", def.get("mode").asText());
        assertEquals(List.of("dueDate", "title", "amount"),
                List.of(def.get("columns").get(0).asText(), def.get("columns").get(1).asText(),
                        def.get("columns").get(2).asText()));
        assertEquals(1, def.get("filters").size());
        assertEquals(BuiltinWidgetRegistry.UPCOMING_DEFAULT_DAYS, horizonDays(def));
        assertEquals("dueDate", def.get("sort").get(0).get("key").asText());
        assertEquals("asc", def.get("sort").get(0).get("direction").asText());

        assertEquals(1, e.params().size());
        ParamSpec days = e.params().get(0);
        assertEquals("days", days.name());
        assertEquals(BuiltinWidgetRegistry.PARAM_INT, days.type());
        assertFalse(days.required());
        assertEquals(14, days.defaultValue().asInt());
        assertEquals(1, days.min());
        assertEquals(90, days.max());
    }

    @Test
    void billsDueIsAComponentWithAnOptionalAccountIdParam() {
        Entry e = registry.require(BuiltinWidgetRegistry.BILLS_DUE);
        assertEquals("Bills due", e.label());
        assertEquals(BuiltinWidgetRegistry.KIND_COMPONENT, e.kind());
        assertEquals(100, e.minW());
        assertNull(e.href());
        assertNull(e.templateType());
        assertNull(e.datasource());
        assertNull(e.templateDefinition());
        assertEquals(1, e.params().size());
        ParamSpec accountId = e.params().get(0);
        assertEquals("accountId", accountId.name());
        assertEquals(BuiltinWidgetRegistry.PARAM_UUID, accountId.type());
        assertFalse(accountId.required());
        assertNull(accountId.defaultValue());
        assertNull(accountId.min());
        assertNull(accountId.max());
    }

    // ------------------------------------------------------------------ lookup

    @Test
    void findIsEmptyForNullAndUnknownKeys() {
        assertTrue(registry.find(null).isEmpty());
        assertTrue(registry.find("nope").isEmpty());
        assertTrue(registry.find("upcoming").isPresent());
    }

    @Test
    void isKnownOnlyForRegisteredKeys() {
        assertTrue(registry.isKnown("net_worth"));
        assertTrue(registry.isKnown("bills_due"));
        assertFalse(registry.isKnown(null));
        assertFalse(registry.isKnown("NET_WORTH"));
        assertFalse(registry.isKnown("nope"));
    }

    @Test
    void requireUnknownKeyIs404() {
        assertThrows(ResourceNotFoundException.class, () -> registry.require("nope"));
    }

    // ------------------------------------------------------------------ validateParams

    @Test
    void absentParamsAreAccepted() {
        Entry upcoming = registry.require("upcoming");
        assertDoesNotThrow(() -> registry.validateParams(upcoming, null));
        assertDoesNotThrow(() -> registry.validateParams(upcoming, NullNode.getInstance()));
        assertDoesNotThrow(() -> registry.validateParams(upcoming, params()));
    }

    @Test
    void nonObjectParamsAreRejected() {
        Entry upcoming = registry.require("upcoming");
        assertThrows(ValidationException.class, () -> registry.validateParams(upcoming, mapper.createArrayNode()));
        assertThrows(ValidationException.class,
                () -> registry.validateParams(upcoming, mapper.getNodeFactory().textNode("days")));
    }

    @Test
    void undeclaredParamIsRejected() {
        assertThrows(ValidationException.class,
                () -> registry.validateParams(registry.require("upcoming"), params().put("months", 2)));
        assertThrows(ValidationException.class,
                () -> registry.validateParams(registry.require("net_worth"), params().put("days", 14)));
        assertThrows(ValidationException.class,
                () -> registry.validateParams(registry.require("attention"), params().put("x", 1)));
    }

    @Test
    void intParamMustBeIntegral() {
        Entry upcoming = registry.require("upcoming");
        assertThrows(ValidationException.class, () -> registry.validateParams(upcoming, params().put("days", 1.5)));
        assertThrows(ValidationException.class, () -> registry.validateParams(upcoming, params().put("days", "14")));
        assertThrows(ValidationException.class, () -> registry.validateParams(upcoming, params().put("days", true)));
    }

    @Test
    void intParamMustBeWithinMinAndMaxInclusive() {
        Entry upcoming = registry.require("upcoming");
        assertDoesNotThrow(() -> registry.validateParams(upcoming, params().put("days", 1)));
        assertDoesNotThrow(() -> registry.validateParams(upcoming, params().put("days", 90)));
        assertThrows(ValidationException.class, () -> registry.validateParams(upcoming, params().put("days", 0)));
        assertThrows(ValidationException.class, () -> registry.validateParams(upcoming, params().put("days", 91)));
        assertThrows(ValidationException.class, () -> registry.validateParams(upcoming, params().put("days", -5)));
    }

    @Test
    void explicitNullOptionalParamIsTreatedAsAbsent() {
        assertDoesNotThrow(() -> registry.validateParams(registry.require("upcoming"), params().putNull("days")));
        assertDoesNotThrow(() -> registry.validateParams(registry.require("bills_due"), params().putNull("accountId")));
    }

    @Test
    void uuidParamMustBeAParseableUuidString() {
        Entry bills = registry.require("bills_due");
        assertDoesNotThrow(() -> registry.validateParams(bills,
                params().put("accountId", "8f14e45f-ceea-467f-a0c4-8d4a1c3b2e10")));
        assertThrows(ValidationException.class, () -> registry.validateParams(bills, params().put("accountId", "not-a-uuid")));
        assertThrows(ValidationException.class, () -> registry.validateParams(bills, params().put("accountId", 42)));
    }

    @Test
    void requiredParamMustBePresent() {
        Entry entry = new Entry("custom", "Custom", "d", 10, BuiltinWidgetRegistry.KIND_COMPONENT, null, null, null,
                null, List.of(new ParamSpec("accountId", BuiltinWidgetRegistry.PARAM_UUID, true, null, null, null)), null);
        assertThrows(ValidationException.class, () -> registry.validateParams(entry, null));
        assertThrows(ValidationException.class, () -> registry.validateParams(entry, params()));
        assertThrows(ValidationException.class, () -> registry.validateParams(entry, params().putNull("accountId")));
        assertDoesNotThrow(() -> registry.validateParams(entry,
                params().put("accountId", "8f14e45f-ceea-467f-a0c4-8d4a1c3b2e10")));
    }

    @Test
    void intParamWithoutBoundsAcceptsAnyInteger() {
        Entry entry = new Entry("custom", "Custom", "d", 10, BuiltinWidgetRegistry.KIND_COMPONENT, null, null, null,
                null, List.of(new ParamSpec("n", BuiltinWidgetRegistry.PARAM_INT, false, null, null, null)), null);
        assertDoesNotThrow(() -> registry.validateParams(entry, params().put("n", -1000)));
        assertDoesNotThrow(() -> registry.validateParams(entry, params().put("n", 1000)));
    }

    @Test
    void unknownParamTypeIsAProgrammingError() {
        Entry entry = new Entry("custom", "Custom", "d", 10, BuiltinWidgetRegistry.KIND_COMPONENT, null, null, null,
                null, List.of(new ParamSpec("flag", "bool", false, null, null, null)), null);
        assertThrows(IllegalStateException.class, () -> registry.validateParams(entry, params().put("flag", true)));
    }

    // ------------------------------------------------------------------ resolveDefinition

    @Test
    void resolveDefinitionRejectsComponents() {
        assertThrows(ValidationException.class, () -> registry.resolveDefinition(registry.require("attention"), null));
        assertThrows(ValidationException.class, () -> registry.resolveDefinition(registry.require("bills_due"), null));
    }

    @Test
    void resolveDefinitionRejectsATemplateEntryWithoutADefinition() {
        Entry entry = new Entry("broken", "Broken", "d", 10, BuiltinWidgetRegistry.KIND_TEMPLATE, ReportType.KPI,
                "net_worth", null, null, List.of(), null);
        assertThrows(ValidationException.class, () -> registry.resolveDefinition(entry, null));
    }

    @Test
    void upcomingDefaultsToFourteenDaysWhenParamsAreAbsent() {
        Entry upcoming = registry.require("upcoming");
        assertEquals(14, horizonDays(registry.resolveDefinition(upcoming, null)));
        assertEquals(14, horizonDays(registry.resolveDefinition(upcoming, NullNode.getInstance())));
        assertEquals(14, horizonDays(registry.resolveDefinition(upcoming, params())));
        assertEquals(14, horizonDays(registry.resolveDefinition(upcoming, params().putNull("days"))));
    }

    @Test
    void upcomingSubstitutesTheDaysParamIntoNextXDays() {
        Entry upcoming = registry.require("upcoming");
        assertEquals(30, horizonDays(registry.resolveDefinition(upcoming, params().put("days", 30))));
        assertEquals(1, horizonDays(registry.resolveDefinition(upcoming, params().put("days", 1))));
        assertEquals(90, horizonDays(registry.resolveDefinition(upcoming, params().put("days", 90))));
    }

    @Test
    void resolveDefinitionNeverMutatesTheTemplate() {
        Entry upcoming = registry.require("upcoming");
        JsonNode resolved = registry.resolveDefinition(upcoming, params().put("days", 45));
        assertNotSame(upcoming.templateDefinition(), resolved);
        assertEquals(14, horizonDays(upcoming.templateDefinition()));
        assertEquals(14, horizonDays(registry.resolveDefinition(upcoming, null)));

        Entry netWorth = registry.require("net_worth");
        ObjectNode copy = (ObjectNode) registry.resolveDefinition(netWorth, null);
        copy.put("measure", "changed");
        assertEquals("signedValue", netWorth.templateDefinition().get("measure").asText());
    }

    @Test
    void resolveDefinitionValidatesParamsFirst() {
        Entry upcoming = registry.require("upcoming");
        assertThrows(ValidationException.class, () -> registry.resolveDefinition(upcoming, params().put("days", 0)));
        assertThrows(ValidationException.class, () -> registry.resolveDefinition(upcoming, params().put("other", 1)));
        assertThrows(ValidationException.class,
                () -> registry.resolveDefinition(registry.require("net_worth"), params().put("days", 3)));
    }

    @Test
    void netWorthResolvesToItsTemplateUnchanged() {
        JsonNode resolved = registry.resolveDefinition(registry.require("net_worth"), null);
        assertEquals(registry.require("net_worth").templateDefinition(), resolved);
    }

    @Test
    void upcomingApplierOnlyRewritesTheDueDateNextXDaysFilter() {
        ObjectNode def = mapper.createObjectNode();
        var filters = def.putArray("filters");
        filters.addObject().put("field", "dueDate").put("operator", "this_month");
        filters.addObject().put("field", "kind").put("operator", "is").put("value", "emi");
        ObjectNode horizon = filters.addObject().put("field", "dueDate").put("operator", "next_x_days");
        horizon.putObject("value").put("amount", 3);

        registry.require("upcoming").paramApplier().apply(def, params().put("days", 21));

        assertFalse(def.get("filters").get(0).has("value"));
        assertEquals("emi", def.get("filters").get(1).get("value").asText());
        assertEquals(21, def.get("filters").get(2).get("value").get("amount").asInt());
    }

    // ------------------------------------------------------------------ intParam

    @Test
    void intParamFallsBackWhenAbsentOrNotIntegral() {
        assertEquals(7, BuiltinWidgetRegistry.intParam(null, "days", 7));
        assertEquals(7, BuiltinWidgetRegistry.intParam(params(), "days", 7));
        assertEquals(7, BuiltinWidgetRegistry.intParam(params().put("days", "30"), "days", 7));
        assertEquals(7, BuiltinWidgetRegistry.intParam(params().put("days", 2.5), "days", 7));
        assertEquals(30, BuiltinWidgetRegistry.intParam(params().put("days", 30), "days", 7));
    }

    // ------------------------------------------------------------------ templates run on the real engine

    @Test
    void templatesAreValidDefinitionsForTheirDatasources() {
        DatasourceRegistry datasources = new DatasourceRegistry(List.of(
                new NetWorthDatasource(mock(AccountService.class), mock(LoanService.class), mock(LendingService.class)),
                new ObligationsDatasource(mock(ObligationsService.class))), new DatasourceCatalog());
        ReportDefinitionValidator validator = new ReportDefinitionValidator(datasources);

        for (Entry entry : registry.all()) {
            if (!entry.isTemplate()) {
                continue;
            }
            JsonNode resolved = registry.resolveDefinition(entry, null);
            assertDoesNotThrow(() -> validator.validate(entry.datasource(),
                    ReportDefinitions.parse(entry.templateType(), resolved, mapper)), entry.key());
        }
        for (int days : new int[] {1, 90}) {
            JsonNode resolved = registry.resolveDefinition(registry.require("upcoming"), params().put("days", days));
            assertDoesNotThrow(() -> validator.validate("obligations",
                    ReportDefinitions.parse(ReportType.TABLE, resolved, mapper)));
        }
    }
}
