package com.financeos.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.report.breakdown.RowBreakdownResponse;
import com.financeos.domain.report.breakdown.RowBreakdownService;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ReportBreakdownControllerTest {

    private RowBreakdownService service;
    private ReportBreakdownController controller;

    @BeforeEach
    void setUp() {
        service = mock(RowBreakdownService.class);
        controller = new ReportBreakdownController(service);
    }

    @Test
    void breakdownPassesTheRawSizeThroughAndAnswers200() {
        RowBreakdownResponse body = new RowBreakdownResponse("net_worth", "r", "t", null, null, BigDecimal.ONE, "x",
                "currency", LocalDate.of(2026, 10, 8), List.of(), List.of(), List.of());
        when(service.breakdown("net_worth", "r", null)).thenReturn(body);

        ResponseEntity<RowBreakdownResponse> response = controller.rowBreakdown("net_worth", "r", null);

        assertEquals(200, response.getStatusCode().value());
        assertSame(body, response.getBody());
    }

    @Test
    void sectionPassesTheRawPagingThroughAndAnswers200() {
        ReportData table = new TableData("TABLE", "raw", List.of(), List.of(), new TableData.Page(2, 10, 0, 1));
        when(service.section("net_worth", "r", "entries", 2, 10)).thenReturn(table);

        ResponseEntity<ReportData> response = controller.rowBreakdownSection("net_worth", "r", "entries", 2, 10);

        assertEquals(200, response.getStatusCode().value());
        assertSame(table, response.getBody());
    }
}
