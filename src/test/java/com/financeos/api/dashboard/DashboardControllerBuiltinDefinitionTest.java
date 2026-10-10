package com.financeos.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.BuiltinDataRequest;
import com.financeos.api.dashboard.dto.BuiltinDefinitionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.dashboard.BuiltinAvailabilityService;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry;
import com.financeos.domain.dashboard.DashboardService;
import com.financeos.domain.dashboard.HomeDashboardSeeder;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST /dashboards/builtins/{key}/definition: the definition the data endpoint would run for the
 * same params, with the request-time filters applied, for "Duplicate as my report".
 */
class DashboardControllerBuiltinDefinitionTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private final ObjectMapper mapper = new ObjectMapper();
    private BuiltinWidgetRegistry registry;
    private ReportDataService reportDataService;
    private DashboardController controller;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(2, 0).atZone(IST).toInstant(), IST));
        registry = new BuiltinWidgetRegistry(mapper);
        reportDataService = mock(ReportDataService.class);
        controller = new DashboardController(mock(DashboardService.class), mock(HomeDashboardSeeder.class), registry,
                reportDataService,
                new BuiltinAvailabilityService(mock(com.financeos.domain.account.AccountRepository.class),
                        mock(com.financeos.domain.loan.LoanRepository.class),
                        mock(com.financeos.domain.holding.HoldingRepository.class)));
        UserContext.setCurrentUserId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private BuiltinDefinitionResponse resolve(String key, BuiltinDataRequest request) {
        return controller.resolveBuiltinDefinition(key, request).getBody();
    }

    private static List<JsonNode> filters(JsonNode definition, String field) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode f : definition.get("filters")) {
            if (field.equals(f.path("field").asText())) {
                found.add(f);
            }
        }
        return found;
    }

    private static JsonNode only(JsonNode definition, String field) {
        List<JsonNode> found = filters(definition, field);
        assertEquals(1, found.size(), "one " + field + " filter in " + definition);
        return found.get(0);
    }

    // ------------------------------------------------------------------ relative windows stay relative

    @Test
    void upcomingCarriesItsDaysAsTheRelativeNextXDaysPreset() {
        BuiltinDefinitionResponse r = resolve("upcoming", new BuiltinDataRequest(mapper.createObjectNode().put("days", 30)));

        assertEquals("upcoming", r.key());
        assertEquals("Upcoming", r.label());
        assertEquals("TABLE", r.type());
        assertEquals("obligations", r.datasource());
        JsonNode horizon = only(r.definition(), "dueDate");
        assertEquals("next_x_days", horizon.get("operator").asText());
        assertEquals(30, horizon.get("value").get("amount").asInt());
        assertNull(r.windowAsOf(), "a relative preset keeps the saved report current");
        verifyNoInteractions(reportDataService);
    }

    @Test
    void absentOrNullParamsResolveTheDefaults() {
        for (BuiltinDataRequest request : java.util.Arrays.asList(null, new BuiltinDataRequest(null))) {
            BuiltinDefinitionResponse r = resolve("upcoming", request);
            assertEquals(BuiltinWidgetRegistry.UPCOMING_DEFAULT_DAYS,
                    only(r.definition(), "dueDate").get("value").get("amount").asInt());
        }
        JsonNode heatmap = resolve("spend_heatmap", null).definition();
        assertEquals(BuiltinWidgetRegistry.HEATMAP_DEFAULT_MONTHS,
                only(heatmap, "date").get("value").get("amount").asInt());
    }

    @Test
    void spendHeatmapCarriesItsMonthsAndKeepsTheSpendFilters() {
        BuiltinDefinitionResponse r = resolve("spend_heatmap",
                new BuiltinDataRequest(mapper.createObjectNode().put("months", 3)));

        assertEquals("CHART", r.type());
        assertEquals("transactions", r.datasource());
        JsonNode window = only(r.definition(), "date");
        assertEquals("last_x_months", window.get("operator").asText());
        assertEquals(3, window.get("value").get("amount").asInt());
        assertEquals("DEBIT", only(r.definition(), "type").get("value").asText());
        assertFalse(only(r.definition(), "isExcluded").get("value").asBoolean());
        assertFalse(only(r.definition(), "isTransferLeg").get("value").asBoolean());
        assertEquals("day", r.definition().get("dimension").get("granularity").asText());
        assertNull(r.windowAsOf());
    }

    @Test
    void rewardsEarnedKeepsTheCurrentFinancialYearPreset() {
        BuiltinDefinitionResponse r = resolve("rewards_earned", null);

        assertEquals("TABLE", r.type());
        assertEquals("reward_earnings", r.datasource());
        assertEquals("current_fy", only(r.definition(), "effectiveDate").get("operator").asText());
        assertNull(r.windowAsOf());
    }

    @Test
    void netWorthAndAllocationResolveWithNoDateWindow() {
        BuiltinDefinitionResponse netWorth = resolve("net_worth", null);
        assertEquals("KPI", netWorth.type());
        assertEquals("net_worth", netWorth.datasource());
        assertEquals("signedValue", netWorth.definition().get("measure").asText());
        assertNull(netWorth.windowAsOf());

        BuiltinDefinitionResponse allocation = resolve("allocation", null);
        assertEquals("CHART", allocation.type());
        assertEquals("positions", allocation.datasource());
        assertTrue(only(allocation.definition(), "isOpen").get("value").asBoolean());
        assertNull(allocation.windowAsOf());
    }

    // ------------------------------------------------------------------ today's reward windows are pinned

    @Test
    void milestoneProgressPinsTodaysWindowAndTheCardFilter() {
        String card = UUID.randomUUID().toString();
        BuiltinDefinitionResponse r = resolve("milestone_progress",
                new BuiltinDataRequest(mapper.createObjectNode().put("accountId", card)));

        assertEquals("TABLE", r.type());
        assertEquals("reward_milestones", r.datasource());
        JsonNode start = only(r.definition(), "windowStart");
        assertEquals("before", start.get("operator").asText());
        assertEquals("2026-10-11", start.get("value").asText(), "IST today, not the UTC date");
        JsonNode end = only(r.definition(), "windowEnd");
        assertEquals("after", end.get("operator").asText());
        assertEquals("2026-10-09", end.get("value").asText());
        assertEquals(card, only(r.definition(), "card").get("value").asText());
        assertEquals("No", only(r.definition(), "achieved").get("value").asText());
        assertEquals(TODAY, r.windowAsOf(), "the duplicate is a snapshot of today's window");
    }

    @Test
    void capHeadroomPinsTodaysWindowAndHasNoCardFilterForAllCards() {
        BuiltinDefinitionResponse r = resolve("cap_headroom", null);

        assertEquals("reward_caps", r.datasource());
        assertEquals("2026-10-11", only(r.definition(), "windowStart").get("value").asText());
        assertEquals("2026-10-09", only(r.definition(), "windowEnd").get("value").asText());
        assertTrue(filters(r.definition(), "card").isEmpty());
        assertEquals("utilizationPct", r.definition().get("sort").get(0).get("key").asText());
        assertEquals(TODAY, r.windowAsOf());
    }

    @Test
    void onlyTheRewardWindowTemplatesPinToday() {
        for (BuiltinWidgetRegistry.Entry entry : registry.all()) {
            boolean pinned = BuiltinWidgetRegistry.pinsTodaysWindow(entry);
            assertEquals(entry.key().equals("milestone_progress") || entry.key().equals("cap_headroom"), pinned,
                    entry.key());
        }
    }

    // ------------------------------------------------------------------ same as the data endpoint

    @Test
    void everyTemplateResolvesExactlyWhatTheDataEndpointRuns() {
        String card = UUID.randomUUID().toString();
        for (BuiltinWidgetRegistry.Entry entry : registry.all()) {
            if (!entry.isTemplate()) {
                continue;
            }
            JsonNode params = entry.params().stream().anyMatch(p -> p.name().equals("accountId"))
                    ? mapper.createObjectNode().put("accountId", card)
                    : null;
            BuiltinDataRequest request = new BuiltinDataRequest(params);
            BuiltinDefinitionResponse resolved = resolve(entry.key(), request);

            controller.runBuiltin(entry.key(), request, null, null, null);
            ArgumentCaptor<JsonNode> ran = ArgumentCaptor.forClass(JsonNode.class);
            verify(reportDataService).runDefinition(eq(entry.templateType()), eq(entry.datasource()), ran.capture(),
                    any(), any(), any());
            assertEquals(ran.getValue(), resolved.definition(), entry.key());
            assertEquals(ReportType.valueOf(resolved.type()), entry.templateType());
            org.mockito.Mockito.clearInvocations(reportDataService);
        }
    }

    // ------------------------------------------------------------------ errors

    @Test
    void componentBuiltinsAre400() {
        assertThrows(ValidationException.class, () -> resolve("attention", null));
        assertThrows(ValidationException.class, () -> resolve("card_utilisation", null));
        assertThrows(ValidationException.class, () -> resolve("shortcuts", null));
    }

    @Test
    void unknownBuiltinIs404() {
        assertThrows(ResourceNotFoundException.class, () -> resolve("cash_flow", null));
    }

    @Test
    void invalidParamsAre400() {
        assertThrows(ValidationException.class, () -> resolve("spend_heatmap",
                new BuiltinDataRequest(mapper.createObjectNode().put("months", 13))));
        assertThrows(ValidationException.class, () -> resolve("milestone_progress",
                new BuiltinDataRequest(mapper.createObjectNode().put("accountId", "not-a-uuid"))));
        assertThrows(ValidationException.class, () -> resolve("upcoming",
                new BuiltinDataRequest(mapper.createObjectNode().put("months", 3))));
    }

    @Test
    void unauthenticatedIs401() {
        UserContext.clear();
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> resolve("upcoming", null));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }
}
