package com.financeos.api.report;

import com.financeos.domain.report.datasource.DatasourceCatalog.ReportCatalogView;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the reportable field + operator catalog the GUI uses to build reports.
 */
@RestController
@RequestMapping("/api/v1/report")
public class ReportDatasourceController {

    private final DatasourceRegistry registry;

    public ReportDatasourceController(DatasourceRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/datasource")
    public ResponseEntity<ReportCatalogView> datasource() {
        return ResponseEntity.ok(registry.view());
    }
}
