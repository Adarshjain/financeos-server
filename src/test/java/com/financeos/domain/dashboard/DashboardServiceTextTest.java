package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.dashboard.dto.UpdateDashboardRequest;
import com.financeos.api.dashboard.dto.WidgetResponse;
import com.financeos.core.exception.ValidationException;
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

/** How {@link DashboardService} renders and persists {@code text} widgets (section headers). */
class DashboardServiceTextTest {

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

    private Dashboard stored(String widgetsJson) {
        Dashboard dashboard = new Dashboard(user, "Home", null, widgetsJson);
        dashboard.setId(UUID.randomUUID());
        when(dashboardRepository.findById(dashboard.getId())).thenReturn(Optional.of(dashboard));
        return dashboard;
    }

    private static DashboardWidget header(String title, JsonNode params) {
        return new DashboardWidget("h1", null, title, new WidgetLayout(0, 0, 100, 4), DashboardWidget.KIND_TEXT, null, params);
    }

    @Test
    void textWidgetCarriesTitleAndDescriptionOnly() {
        WidgetResponse w = service.get(stored("[{\"id\":\"h1\",\"title\":\"Spending\",\"layout\":{\"x\":0,\"y\":0,\"w\":100,\"h\":5},"
                + "\"kind\":\"text\",\"params\":{\"description\":\"Cards only\"}}]").getId()).widgets().get(0);

        assertEquals("text", w.kind());
        assertEquals("Spending", w.title());
        assertEquals("Cards only", w.params().get("description").asText());
        assertEquals(100, w.layout().w());
        assertNull(w.reportId());
        assertNull(w.report());
        assertNull(w.builtinKey());
        assertNull(w.builtin());
    }

    @Test
    void textWidgetWithoutDescriptionHasNullParams() {
        WidgetResponse w = service.get(stored("[{\"id\":\"h1\",\"title\":\"Spending\",\"layout\":{\"x\":0,\"y\":0,\"w\":100,\"h\":4},"
                + "\"kind\":\"text\",\"params\":null}]").getId()).widgets().get(0);
        assertEquals("text", w.kind());
        assertNull(w.params());
    }

    @Test
    void textWidgetsNeverTouchTheReportRepository() {
        UUID strayReportId = UUID.randomUUID();
        WidgetResponse w = service.get(stored("[{\"id\":\"h1\",\"reportId\":\"" + strayReportId
                + "\",\"title\":\"A\",\"layout\":{\"x\":0,\"y\":0,\"w\":100,\"h\":4},\"kind\":\"text\"}]").getId())
                .widgets().get(0);

        assertNull(w.reportId());
        verify(reportRepository, never()).findAllById(anyCollection());
    }

    @Test
    void mixedDashboardKeepsOrderAndLooksUpOnlyReportIds() {
        UUID reportId = UUID.randomUUID();
        Report report = new Report(user, "Spend", ReportType.CHART, "transactions", "{}");
        report.setId(reportId);
        when(reportRepository.findAllById(Set.of(reportId))).thenReturn(List.of(report));

        List<WidgetResponse> widgets = service.get(stored("["
                + "{\"id\":\"h1\",\"title\":\"This month\",\"layout\":{\"x\":0,\"y\":0,\"w\":100,\"h\":4},\"kind\":\"text\"},"
                + "{\"id\":\"nw\",\"layout\":{\"x\":0,\"y\":4,\"w\":50,\"h\":16},\"kind\":\"builtin\",\"builtinKey\":\"net_worth\"},"
                + "{\"id\":\"h2\",\"title\":\"Spending\",\"layout\":{\"x\":0,\"y\":20,\"w\":100,\"h\":4},\"kind\":\"text\"},"
                + "{\"id\":\"s\",\"reportId\":\"" + reportId + "\",\"layout\":{\"x\":0,\"y\":24,\"w\":100,\"h\":28}}"
                + "]").getId()).widgets();

        assertEquals(List.of("text", "builtin", "text", "report"), widgets.stream().map(WidgetResponse::kind).toList());
        assertEquals("Spend", widgets.get(3).report().name());
        verify(reportRepository).findAllById(Set.of(reportId));
    }

    @Test
    void createPersistsTextKindTitleAndDescription() throws Exception {
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(dashboardRepository.save(any(Dashboard.class))).thenAnswer(inv -> inv.getArgument(0));

        DashboardResponse response = service.create(new CreateDashboardRequest("D", null, false,
                List.of(header("Investments", mapper.createObjectNode().put("description", "Mutual funds")))));

        ArgumentCaptor<Dashboard> saved = ArgumentCaptor.forClass(Dashboard.class);
        verify(dashboardRepository).save(saved.capture());
        JsonNode stored = mapper.readTree(saved.getValue().getWidgets()).get(0);
        assertEquals("text", stored.get("kind").asText());
        assertEquals("Investments", stored.get("title").asText());
        assertEquals("Mutual funds", stored.get("params").get("description").asText());
        assertEquals("text", response.widgets().get(0).kind());
    }

    @Test
    void updateRejectsAHeaderWithoutATitleAndSavesNothing() {
        Dashboard dashboard = stored("[]");

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update(dashboard.getId(),
                new UpdateDashboardRequest("D", null, false, List.of(header(" ", null)))));

        assertTrue(ex.getMessage().contains("requires a title"));
        verify(dashboardRepository, never()).save(any());
    }
}
