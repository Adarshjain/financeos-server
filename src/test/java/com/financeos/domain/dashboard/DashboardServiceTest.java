package com.financeos.domain.dashboard;

import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.UpdateDashboardRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.report.ReportRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

class DashboardServiceTest {

    private DashboardRepository dashboardRepository;
    private ReportRepository reportRepository;
    private UserRepository userRepository;
    private DashboardValidator validator;
    private ObjectMapper mapper;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardRepository = mock(DashboardRepository.class);
        reportRepository = mock(ReportRepository.class);
        userRepository = mock(UserRepository.class);
        validator = mock(DashboardValidator.class);
        mapper = new ObjectMapper();
        dashboardService = new DashboardService(
                dashboardRepository,
                reportRepository,
                userRepository,
                validator,
                mapper
        );
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void create_newDefault_callsClearDefaultForUser() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);
        when(userRepository.getReferenceById(userId)).thenReturn(user);

        CreateDashboardRequest req = new CreateDashboardRequest("New Dash", "Desc", true, List.of());
        Dashboard mockSaved = new Dashboard(user, "New Dash", "Desc", "[]");
        mockSaved.setId(UUID.randomUUID());
        mockSaved.setDefault(true);
        when(dashboardRepository.save(any(Dashboard.class))).thenReturn(mockSaved);

        dashboardService.create(req);

        // Verify that clearDefaultForUser is called because the transient entity isDefault is false initially, and req isDefault is true
        verify(dashboardRepository, times(1)).clearDefaultForUser(userId);
    }

    @Test
    void update_alreadyDefault_doesNotCallClearDefaultForUser() {
        UUID userId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Dashboard existing = new Dashboard(user, "Old Name", "Desc", "[]");
        existing.setId(dashboardId);
        existing.setDefault(true); // already default!

        when(dashboardRepository.findById(dashboardId)).thenReturn(Optional.of(existing));
        when(dashboardRepository.save(any(Dashboard.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateDashboardRequest req = new UpdateDashboardRequest("New Name", "Desc", true, List.of());

        dashboardService.update(dashboardId, req);

        // Verify that clearDefaultForUser was NEVER called because the dashboard was already default
        verify(dashboardRepository, never()).clearDefaultForUser(any());
    }

    @Test
    void update_notPreviouslyDefault_callsClearDefaultForUser() {
        UUID userId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Dashboard existing = new Dashboard(user, "Old Name", "Desc", "[]");
        existing.setId(dashboardId);
        existing.setDefault(false); // not previously default!

        when(dashboardRepository.findById(dashboardId)).thenReturn(Optional.of(existing));
        when(dashboardRepository.save(any(Dashboard.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateDashboardRequest req = new UpdateDashboardRequest("New Name", "Desc", true, List.of());

        dashboardService.update(dashboardId, req);

        // Verify that clearDefaultForUser was called because it is setting a non-default dashboard to default
        verify(dashboardRepository, times(1)).clearDefaultForUser(userId);
    }

    @Test
    void update_setToFalse_doesNotCallClearDefaultForUser() {
        UUID userId = UUID.randomUUID();
        UUID dashboardId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Dashboard existing = new Dashboard(user, "Old Name", "Desc", "[]");
        existing.setId(dashboardId);
        existing.setDefault(true);

        when(dashboardRepository.findById(dashboardId)).thenReturn(Optional.of(existing));
        when(dashboardRepository.save(any(Dashboard.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateDashboardRequest req = new UpdateDashboardRequest("New Name", "Desc", false, List.of());

        dashboardService.update(dashboardId, req);

        // Verify that clearDefaultForUser was NEVER called because we are setting isDefault to false
        verify(dashboardRepository, never()).clearDefaultForUser(any());
    }
}
