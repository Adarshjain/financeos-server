package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.BuiltinRefResponse;
import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.dashboard.dto.WidgetResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportRepository;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** How {@link DashboardService} renders report vs built-in widgets and persists the widget kinds. */
class DashboardServiceBuiltinTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BuiltinWidgetRegistry builtins = new BuiltinWidgetRegistry(mapper);
    private DashboardRepository dashboardRepository;
    private ReportRepository reportRepository;
    private UserRepository userRepository;
    private DashboardService service;
    private final UUID userId = UUID.randomUUID();
    private User user;

    @BeforeEach
    void setUp() {
        dashboardRepository = mock(DashboardRepository.class);
        reportRepository = mock(ReportRepository.class);
        userRepository = mock(UserRepository.class);
        service = new DashboardService(dashboardRepository, reportRepository, userRepository,
                new DashboardValidator(builtins), mapper, builtins);
        UserContext.setCurrentUserId(userId);
        user = new User();
        user.setId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private DashboardResponse getWithWidgets(String widgetsJson) {
        UUID id = UUID.randomUUID();
        Dashboard dashboard = new Dashboard(user, "Home", null, widgetsJson);
        dashboard.setId(id);
        when(dashboardRepository.findById(id)).thenReturn(Optional.of(dashboard));
        return service.get(id);
    }

    private Report report(UUID id, String name, ReportType type) {
        Report r = new Report(user, name, type, "transactions", "{}");
        r.setId(id);
        return r;
    }

    @Test
    void legacyReportWidgetResolvesKindAndReport() {
        UUID reportId = UUID.randomUUID();
        when(reportRepository.findAllById(Set.of(reportId))).thenReturn(List.of(report(reportId, "Spend", ReportType.CHART)));

        DashboardResponse response = getWithWidgets("[{\"id\":\"w1\",\"reportId\":\"" + reportId
                + "\",\"title\":\"My spend\",\"layout\":{\"x\":0,\"y\":0,\"w\":50,\"h\":10}}]");

        WidgetResponse w = response.widgets().get(0);
        assertEquals("report", w.kind());
        assertEquals(reportId, w.reportId());
        assertEquals("My spend", w.title());
        assertEquals("Spend", w.report().name());
        assertEquals(ReportType.CHART, w.report().type());
        assertTrue(w.report().available());
        assertNull(w.builtinKey());
        assertNull(w.params());
        assertNull(w.builtin());
    }

    @Test
    void reportWidgetWhoseReportIsGoneIsUnavailable() {
        UUID reportId = UUID.randomUUID();
        when(reportRepository.findAllById(Set.of(reportId))).thenReturn(List.of());

        WidgetResponse w = getWithWidgets("[{\"id\":\"w1\",\"reportId\":\"" + reportId
                + "\",\"layout\":{\"x\":0,\"y\":0,\"w\":50,\"h\":10},\"kind\":\"report\"}]").widgets().get(0);

        assertEquals("report", w.kind());
        assertFalse(w.report().available());
        assertNull(w.report().name());
        assertNull(w.report().type());
    }

    @Test
    void builtinTemplateWidgetCarriesKeyParamsAndRegistryRef() {
        WidgetResponse w = getWithWidgets("[{\"id\":\"up\",\"layout\":{\"x\":0,\"y\":0,\"w\":100,\"h\":20},"
                + "\"kind\":\"builtin\",\"builtinKey\":\"upcoming\",\"params\":{\"days\":30}}]").widgets().get(0);

        assertEquals("builtin", w.kind());
        assertEquals("upcoming", w.builtinKey());
        assertEquals(30, w.params().get("days").asInt());
        assertNull(w.reportId());
        assertNull(w.report());
        BuiltinRefResponse ref = w.builtin();
        assertEquals("upcoming", ref.key());
        assertEquals("Upcoming", ref.label());
        assertEquals(100, ref.minW());
        assertEquals("template", ref.kind());
        assertEquals("TABLE", ref.templateType());
        assertEquals("/upcoming", ref.href());
    }

    @Test
    void builtinComponentWidgetHasNoTemplateType() {
        WidgetResponse w = getWithWidgets("[{\"id\":\"at\",\"layout\":{\"x\":0,\"y\":0,\"w\":50,\"h\":20},"
                + "\"kind\":\"builtin\",\"builtinKey\":\"attention\"}]").widgets().get(0);

        BuiltinRefResponse ref = w.builtin();
        assertEquals("attention", ref.key());
        assertEquals("Inbox", ref.label());
        assertEquals("component", ref.kind());
        assertNull(ref.templateType());
        assertEquals("/inbox", ref.href());
        assertNull(w.params());
    }

    @Test
    void builtinWidgetWithExplicitNullParamsReturnsNullParams() {
        WidgetResponse w = getWithWidgets("[{\"id\":\"nw\",\"layout\":{\"x\":0,\"y\":0,\"w\":50,\"h\":20},"
                + "\"kind\":\"builtin\",\"builtinKey\":\"net_worth\",\"params\":null}]").widgets().get(0);
        assertNull(w.params());
        assertEquals("KPI", w.builtin().templateType());
    }

    @Test
    void builtinWidgetWithAnUnregisteredKeyHasNullBuiltin() {
        WidgetResponse w = getWithWidgets("[{\"id\":\"old\",\"title\":\"Old\",\"layout\":{\"x\":0,\"y\":0,\"w\":50,\"h\":20},"
                + "\"kind\":\"builtin\",\"builtinKey\":\"retired_widget\"}]").widgets().get(0);
        assertEquals("builtin", w.kind());
        assertEquals("retired_widget", w.builtinKey());
        assertEquals("Old", w.title());
        assertNull(w.builtin());
        assertNull(w.report());
    }

    @Test
    void builtinWidgetsNeverTouchTheReportRepository() {
        UUID strayReportId = UUID.randomUUID();
        WidgetResponse w = getWithWidgets("[{\"id\":\"nw\",\"reportId\":\"" + strayReportId
                + "\",\"layout\":{\"x\":0,\"y\":0,\"w\":50,\"h\":20},\"kind\":\"builtin\",\"builtinKey\":\"net_worth\"}]")
                .widgets().get(0);

        assertNull(w.reportId());
        verify(reportRepository, never()).findAllById(anyCollection());
    }

    @Test
    void mixedDashboardLooksUpOnlyReportWidgetIds() {
        UUID reportId = UUID.randomUUID();
        when(reportRepository.findAllById(Set.of(reportId))).thenReturn(List.of(report(reportId, "Spend", ReportType.CHART)));

        List<WidgetResponse> widgets = getWithWidgets("["
                + "{\"id\":\"nw\",\"layout\":{\"x\":0,\"y\":0,\"w\":50,\"h\":16},\"kind\":\"builtin\",\"builtinKey\":\"net_worth\"},"
                + "{\"id\":\"s\",\"reportId\":\"" + reportId + "\",\"layout\":{\"x\":0,\"y\":16,\"w\":100,\"h\":28}}"
                + "]").widgets();

        assertEquals(List.of("builtin", "report"), widgets.stream().map(WidgetResponse::kind).toList());
        verify(reportRepository).findAllById(Set.of(reportId));
    }

    @Test
    void toBuiltinRefMapsEveryEntryField() {
        for (BuiltinWidgetRegistry.Entry entry : builtins.all()) {
            BuiltinRefResponse ref = DashboardService.toBuiltinRef(entry);
            assertEquals(entry.key(), ref.key());
            assertEquals(entry.label(), ref.label());
            assertEquals(entry.minW(), ref.minW());
            assertEquals(entry.kind(), ref.kind());
            assertEquals(entry.templateType() == null ? null : entry.templateType().name(), ref.templateType());
            assertEquals(entry.href(), ref.href());
        }
    }

    @Test
    void createPersistsKindBuiltinKeyAndParams() throws Exception {
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(dashboardRepository.save(any(Dashboard.class))).thenAnswer(inv -> inv.getArgument(0));
        DashboardWidget upcoming = new DashboardWidget("up", null, null, new WidgetLayout(0, 0, 100, 20),
                DashboardWidget.KIND_BUILTIN, "upcoming", mapper.createObjectNode().put("days", 7));

        DashboardResponse response = service.create(new CreateDashboardRequest("D", null, false, List.of(upcoming)));

        ArgumentCaptor<Dashboard> saved = ArgumentCaptor.forClass(Dashboard.class);
        verify(dashboardRepository).save(saved.capture());
        JsonNode stored = mapper.readTree(saved.getValue().getWidgets()).get(0);
        assertEquals("builtin", stored.get("kind").asText());
        assertEquals("upcoming", stored.get("builtinKey").asText());
        assertEquals(7, stored.get("params").get("days").asInt());
        assertEquals("upcoming", response.widgets().get(0).builtin().key());
    }
}
