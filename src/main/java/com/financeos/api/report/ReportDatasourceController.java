package com.financeos.api.report;

import com.financeos.api.report.dto.ReportFieldValuesResponse;
import com.financeos.domain.report.ReportFieldValuesService;
import com.financeos.domain.report.datasource.DatasourceCatalog.ReportCatalogView;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the reportable field + operator catalog the GUI uses to build reports.
 */
@RestController
@RequestMapping("/api/v1/report")
public class ReportDatasourceController {

    private final DatasourceRegistry registry;
    private final ReportFieldValuesService fieldValuesService;

    public ReportDatasourceController(DatasourceRegistry registry, ReportFieldValuesService fieldValuesService) {
        this.registry = registry;
        this.fieldValuesService = fieldValuesService;
    }

    @GetMapping("/datasource")
    public ResponseEntity<ReportCatalogView> datasource() {
        return ResponseEntity.ok(registry.view());
    }

    /** Values for the filter dropdowns of the datasource's dynamic enum fields. */
    @GetMapping("/datasource/{name}/values")
    public ResponseEntity<ReportFieldValuesResponse> reportFieldValues(@PathVariable String name) {
        return ResponseEntity.ok(new ReportFieldValuesResponse(fieldValuesService.values(name)));
    }
}
