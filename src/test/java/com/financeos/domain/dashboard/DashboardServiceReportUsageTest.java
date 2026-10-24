package com.financeos.domain.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.report.dto.ReportDashboardRef;
import com.financeos.domain.report.ReportRepository;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

class DashboardServiceReportUsageTest {

    @Test
    void dashboardsByReport_skipsADashboardWhoseWidgetsCannotBeParsed() {
        DashboardRepository repo = mock(DashboardRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        DashboardService service = new DashboardService(repo, mock(ReportRepository.class),
                mock(UserRepository.class), mock(DashboardValidator.class), mapper, new BuiltinWidgetRegistry(mapper));
        UUID reportId = UUID.randomUUID();
        Dashboard broken = new Dashboard(null, "Broken", null, "{not json");
        broken.setId(UUID.randomUUID());
        Dashboard good = new Dashboard(null, "Good", null,
                "[{\"id\":\"w1\",\"reportId\":\"" + reportId + "\",\"layout\":{\"x\":0,\"y\":0,\"w\":6,\"h\":4}}]");
        good.setId(UUID.randomUUID());
        when(repo.findAll()).thenReturn(List.of(broken, good));

        Map<UUID, List<ReportDashboardRef>> usage = service.dashboardsByReport();

        assertEquals(List.of(new ReportDashboardRef(good.getId(), "Good")), usage.get(reportId));
        assertFalse(usage.isEmpty());
    }
}
