package com.financeos.api.report;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.report.dto.RunReportRequest;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.engine.ReportData;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The run endpoints hand the {@code sort} query parameter to the data service as given. */
class ReportControllerRunSortTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ReportDataService reportDataService;
    private ReportController controller;

    @BeforeEach
    void setUp() {
        reportDataService = mock(ReportDataService.class);
        controller = new ReportController(mock(ReportService.class), reportDataService, mapper);
    }

    @Test
    void aSavedRunPassesPagingAndSortThrough() {
        UUID id = UUID.randomUUID();
        ReportData data = mock(ReportData.class);
        when(reportDataService.runSaved(id, 2, 25, "amount,desc")).thenReturn(data);

        assertSame(data, controller.runSavedReport(id, 2, 25, "amount,desc").getBody());
    }

    @Test
    void anAdHocRunPassesItsDefinitionPagingAndSortThrough() throws Exception {
        RunReportRequest request = new RunReportRequest(ReportType.TABLE, "transactions",
                mapper.readTree("{\"mode\":\"raw\",\"columns\":[\"date\"]}"));
        ReportData data = mock(ReportData.class);
        when(reportDataService.runDefinition(ReportType.TABLE, "transactions", request.definition(), 0, 50, "date,asc"))
                .thenReturn(data);

        assertSame(data, controller.runAdHocReport(request, 0, 50, "date,asc").getBody());
    }
}
