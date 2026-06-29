package com.financeos.api.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.report.dto.CreateReportRequest;
import com.financeos.api.report.dto.ReportResponse;
import com.financeos.api.report.dto.ReportSummaryResponse;
import com.financeos.api.report.dto.RunReportRequest;
import com.financeos.api.report.dto.UpdateReportRequest;
import com.financeos.domain.report.Report;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportService;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.engine.ReportData;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;
    private final ReportDataService reportDataService;
    private final ObjectMapper mapper;

    public ReportController(ReportService reportService, ReportDataService reportDataService, ObjectMapper mapper) {
        this.reportService = reportService;
        this.reportDataService = reportDataService;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<ReportResponse> createReport(@Valid @RequestBody CreateReportRequest request) {
        Report report = reportService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportResponse.from(report, mapper));
    }

    @GetMapping
    public ResponseEntity<List<ReportSummaryResponse>> listReports(
            @RequestParam(required = false) ReportType type) {
        List<ReportSummaryResponse> responses = reportService.list(type).stream()
                .map(ReportSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReportResponse> getReport(@PathVariable UUID id) {
        Report report = reportService.get(id);
        return ResponseEntity.ok(ReportResponse.from(report, mapper));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ReportResponse> updateReport(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateReportRequest request) {
        Report report = reportService.update(id, request);
        return ResponseEntity.ok(ReportResponse.from(report, mapper));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteReport(@PathVariable UUID id) {
        reportService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Run a saved report and return its computed data ({@code page}/{@code size} apply to tables). */
    @PostMapping("/{id}/data")
    public ResponseEntity<ReportData> runSavedReport(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(reportDataService.runSaved(id, page, size));
    }

    /** Run an ad-hoc (unsaved) report definition and return its computed data. */
    @PostMapping("/data")
    public ResponseEntity<ReportData> runAdHocReport(
            @Valid @RequestBody RunReportRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok(reportDataService.runAdHoc(
                request.type(), request.datasource(), request.definition(), page, size));
    }
}
