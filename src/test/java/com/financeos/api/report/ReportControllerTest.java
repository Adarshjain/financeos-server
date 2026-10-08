package com.financeos.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.report.dto.ReportResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportService;
import com.financeos.domain.report.ReportType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ReportControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ReportService reportService;
    private ReportController controller;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        controller = new ReportController(reportService, mock(ReportDataService.class), mapper);
    }

    @Test
    void duplicateReturns201WithTheCopy() {
        UUID sourceId = UUID.randomUUID();
        Report copy = new Report(null, "Spend (copy)", ReportType.CHART, "transactions",
                "{\"chartType\":\"bar\",\"filters\":[]}");
        copy.setId(UUID.randomUUID());
        copy.setDescription("Debits");
        when(reportService.duplicate(sourceId)).thenReturn(copy);

        ResponseEntity<ReportResponse> response = controller.duplicateReport(sourceId);

        assertEquals(201, response.getStatusCode().value());
        ReportResponse body = response.getBody();
        assertEquals(copy.getId(), body.id());
        assertEquals("Spend (copy)", body.name());
        assertEquals("Debits", body.description());
        assertEquals(ReportType.CHART, body.type());
        assertEquals("transactions", body.datasource());
        assertEquals("bar", body.definition().get("chartType").asText());
    }

    @Test
    void duplicatePropagatesNotFoundAndForbidden() {
        UUID missing = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        when(reportService.duplicate(missing)).thenThrow(new ResourceNotFoundException("Report", missing));
        when(reportService.duplicate(foreign)).thenThrow(new ValidationException("no permission"));

        assertThrows(ResourceNotFoundException.class, () -> controller.duplicateReport(missing));
        assertThrows(ValidationException.class, () -> controller.duplicateReport(foreign));
    }
}
