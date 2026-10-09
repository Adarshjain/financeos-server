package com.financeos.api.report;

import com.financeos.domain.report.breakdown.RowBreakdownService;
import com.financeos.domain.report.engine.ReportData;
import com.financeos.domain.report.engine.TableData;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The section endpoint hands its {@code sort} parameter to the breakdown service as given. */
class ReportBreakdownControllerSortTest {

    @Test
    void sectionPassesTheSortThrough() {
        RowBreakdownService service = mock(RowBreakdownService.class);
        ReportData table = new TableData("TABLE", "raw", List.of(), List.of(), new TableData.Page(0, 25, 0, 1),
                "amount", "desc");
        when(service.section("net_worth", "r", "transactions", 0, 25, "amount,desc")).thenReturn(table);

        ResponseEntity<ReportData> response = new ReportBreakdownController(service)
                .rowBreakdownSection("net_worth", "r", "transactions", 0, 25, "amount,desc");

        assertEquals(200, response.getStatusCode().value());
        assertSame(table, response.getBody());
    }
}
