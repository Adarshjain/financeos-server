package com.financeos.api.report;

import com.financeos.domain.report.breakdown.RowBreakdownResponse;
import com.financeos.domain.report.breakdown.RowBreakdownService;
import com.financeos.domain.report.engine.ReportData;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How one row of a report datasource is made up (opened from a KPI's underlying data). Unknown
 * datasource or one without a breakdown: 400; unknown, foreign or uncounted row, or unknown
 * section: 404. {@code size} defaults to 25 and is capped at 200; {@code page} is 0-based.
 */
@RestController
@RequestMapping("/api/v1/report/datasource/{name}/rows/{rowId}/breakdown")
public class ReportBreakdownController {

    private final RowBreakdownService breakdownService;

    public ReportBreakdownController(RowBreakdownService breakdownService) {
        this.breakdownService = breakdownService;
    }

    /** The row's breakdown with the first page of each section. */
    @GetMapping
    public ResponseEntity<RowBreakdownResponse> rowBreakdown(@PathVariable String name, @PathVariable String rowId,
                                                             @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(breakdownService.breakdown(name, rowId, size));
    }

    /** One page of one breakdown section, as a raw table. */
    @GetMapping("/sections/{section}")
    public ResponseEntity<ReportData> rowBreakdownSection(@PathVariable String name, @PathVariable String rowId,
                                                          @PathVariable String section,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(breakdownService.section(name, rowId, section, page, size));
    }
}
