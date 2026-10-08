package com.financeos.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.BuiltinDataRequest;
import com.financeos.api.dashboard.dto.BuiltinParamResponse;
import com.financeos.api.dashboard.dto.BuiltinWidgetResponse;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry;
import com.financeos.domain.dashboard.DashboardService;
import com.financeos.domain.dashboard.HomeDashboardSeeder;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.engine.ReportData;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class DashboardControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private DashboardService dashboardService;
    private HomeDashboardSeeder seeder;
    private ReportDataService reportDataService;
    private DashboardController controller;

    @BeforeEach
    void setUp() {
        dashboardService = mock(DashboardService.class);
        seeder = mock(HomeDashboardSeeder.class);
        reportDataService = mock(ReportDataService.class);
        controller = new DashboardController(dashboardService, seeder, new BuiltinWidgetRegistry(mapper), reportDataService);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static DashboardResponse dashboard() {
        return new DashboardResponse(UUID.randomUUID(), "Home", null, true, List.of(), Instant.now(), Instant.now());
    }

    private static int horizonDays(JsonNode definition) {
        for (JsonNode filter : definition.get("filters")) {
            if ("next_x_days".equals(filter.path("operator").asText())) {
                return filter.path("value").path("amount").asInt();
            }
        }
        throw new AssertionError("no next_x_days filter");
    }

    // ------------------------------------------------------------------ seeding on read

    @Test
    void listSeedsHomeBeforeListing() {
        List<DashboardResponse> all = List.of(dashboard());
        when(dashboardService.list()).thenReturn(all);

        ResponseEntity<List<DashboardResponse>> response = controller.listDashboards();

        assertEquals(200, response.getStatusCode().value());
        assertSame(all, response.getBody());
        InOrder order = inOrder(seeder, dashboardService);
        order.verify(seeder).seedIfNeeded(userId);
        order.verify(dashboardService).list();
    }

    @Test
    void defaultSeedsHomeBeforeReadingTheDefault() {
        DashboardResponse home = dashboard();
        when(dashboardService.getDefault()).thenReturn(home);

        ResponseEntity<DashboardResponse> response = controller.getDefaultDashboard();

        assertSame(home, response.getBody());
        InOrder order = inOrder(seeder, dashboardService);
        order.verify(seeder).seedIfNeeded(userId);
        order.verify(dashboardService).getDefault();
    }

    @Test
    void unauthenticatedReadsAre401AndNeverSeed() {
        UserContext.clear();
        ResponseStatusException list = assertThrows(ResponseStatusException.class, () -> controller.listDashboards());
        assertEquals(HttpStatus.UNAUTHORIZED, list.getStatusCode());
        ResponseStatusException def = assertThrows(ResponseStatusException.class, () -> controller.getDefaultDashboard());
        assertEquals(HttpStatus.UNAUTHORIZED, def.getStatusCode());
        verifyNoInteractions(seeder, dashboardService);
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void builtinsListsEveryEntryWithItsSchema() {
        List<BuiltinWidgetResponse> body = controller.listBuiltins().getBody();

        assertEquals(List.of("net_worth", "attention", "upcoming", "bills_due"),
                body.stream().map(BuiltinWidgetResponse::key).toList());

        BuiltinWidgetResponse netWorth = body.get(0);
        assertEquals("Net worth", netWorth.label());
        assertEquals(50, netWorth.minW());
        assertEquals("template", netWorth.kind());
        assertEquals("KPI", netWorth.templateType());
        assertEquals("net_worth", netWorth.datasource());
        assertEquals("signedValue", netWorth.templateDefinition().get("measure").asText());
        assertEquals("/accounts", netWorth.href());
        assertEquals(List.of(), netWorth.params());

        BuiltinWidgetResponse attention = body.get(1);
        assertEquals("Inbox", attention.label());
        assertEquals("component", attention.kind());
        assertNull(attention.templateType());
        assertNull(attention.datasource());
        assertNull(attention.templateDefinition());
        assertEquals("/inbox", attention.href());

        BuiltinWidgetResponse upcoming = body.get(2);
        assertEquals(100, upcoming.minW());
        assertEquals("TABLE", upcoming.templateType());
        assertEquals("obligations", upcoming.datasource());
        assertEquals(14, horizonDays(upcoming.templateDefinition()));
        BuiltinParamResponse days = upcoming.params().get(0);
        assertEquals("days", days.name());
        assertEquals("int", days.type());
        assertEquals(false, days.required());
        assertEquals(14, days.defaultValue().asInt());
        assertEquals(1, days.min());
        assertEquals(90, days.max());

        BuiltinWidgetResponse bills = body.get(3);
        assertEquals("component", bills.kind());
        assertEquals(100, bills.minW());
        assertNull(bills.templateDefinition());
        assertNull(bills.href());
        BuiltinParamResponse accountId = bills.params().get(0);
        assertEquals("accountId", accountId.name());
        assertEquals("uuid", accountId.type());
        assertEquals(false, accountId.required());
        assertNull(accountId.defaultValue());
        assertNull(accountId.min());
        assertNull(accountId.max());
    }

    // ------------------------------------------------------------------ template data

    @Test
    void upcomingRunsTheTemplateWithTheDaysParamSubstituted() {
        ReportData data = mock(ReportData.class);
        when(reportDataService.runDefinition(eq(ReportType.TABLE), eq("obligations"), any(), eq(2), eq(25)))
                .thenReturn(data);

        ResponseEntity<ReportData> response = controller.runBuiltin("upcoming",
                new BuiltinDataRequest(mapper.createObjectNode().put("days", 30)), 2, 25);

        assertEquals(200, response.getStatusCode().value());
        assertSame(data, response.getBody());
        ArgumentCaptor<JsonNode> def = ArgumentCaptor.forClass(JsonNode.class);
        verify(reportDataService).runDefinition(eq(ReportType.TABLE), eq("obligations"), def.capture(), eq(2), eq(25));
        assertEquals(30, horizonDays(def.getValue()));
        assertEquals("raw", def.getValue().get("mode").asText());
    }

    @Test
    void upcomingWithoutABodyOrParamsUsesTheDefaultHorizon() {
        controller.runBuiltin("upcoming", null, null, null);
        controller.runBuiltin("upcoming", new BuiltinDataRequest(null), null, null);

        ArgumentCaptor<JsonNode> def = ArgumentCaptor.forClass(JsonNode.class);
        verify(reportDataService, org.mockito.Mockito.times(2))
                .runDefinition(eq(ReportType.TABLE), eq("obligations"), def.capture(), eq(null), eq(null));
        assertEquals(14, horizonDays(def.getAllValues().get(0)));
        assertEquals(14, horizonDays(def.getAllValues().get(1)));
    }

    @Test
    void netWorthRunsItsKpiTemplate() {
        controller.runBuiltin("net_worth", null, null, null);

        ArgumentCaptor<JsonNode> def = ArgumentCaptor.forClass(JsonNode.class);
        verify(reportDataService).runDefinition(eq(ReportType.KPI), eq("net_worth"), def.capture(), eq(null), eq(null));
        assertEquals("signedValue", def.getValue().get("measure").asText());
    }

    @Test
    void componentBuiltinsHaveNoDataAndAre400() {
        assertThrows(ValidationException.class, () -> controller.runBuiltin("attention", null, null, null));
        assertThrows(ValidationException.class, () -> controller.runBuiltin("bills_due",
                new BuiltinDataRequest(mapper.createObjectNode().put("accountId", UUID.randomUUID().toString())),
                null, null));
        verifyNoInteractions(reportDataService);
    }

    @Test
    void unknownBuiltinIs404() {
        assertThrows(ResourceNotFoundException.class, () -> controller.runBuiltin("cash_flow", null, null, null));
        verifyNoInteractions(reportDataService);
    }

    @Test
    void undeclaredOrOutOfRangeParamsAre400() {
        assertThrows(ValidationException.class, () -> controller.runBuiltin("upcoming",
                new BuiltinDataRequest(mapper.createObjectNode().put("months", 3)), null, null));
        assertThrows(ValidationException.class, () -> controller.runBuiltin("upcoming",
                new BuiltinDataRequest(mapper.createObjectNode().put("days", 91)), null, null));
        assertThrows(ValidationException.class, () -> controller.runBuiltin("net_worth",
                new BuiltinDataRequest(mapper.createObjectNode().put("days", 7)), null, null));
        verifyNoInteractions(reportDataService);
    }

    @Test
    void runBuiltinRequiresAUser() {
        UserContext.clear();
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.runBuiltin("upcoming", null, null, null));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verifyNoInteractions(reportDataService);
    }

    // ------------------------------------------------------------------ restore

    @Test
    void restoreReturns201WithTheNewHome() {
        DashboardResponse home = dashboard();
        when(seeder.restore(userId)).thenReturn(home);

        ResponseEntity<DashboardResponse> response = controller.restoreHome();

        assertEquals(201, response.getStatusCode().value());
        assertSame(home, response.getBody());
    }

    @Test
    void restoreRequiresAUser() {
        UserContext.clear();
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> controller.restoreHome());
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
        verifyNoInteractions(seeder);
    }
}
