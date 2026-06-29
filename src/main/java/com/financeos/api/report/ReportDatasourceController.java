package com.financeos.api.report;

import com.financeos.domain.report.datasource.DatasourceCatalog;
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

    private final DatasourceCatalog catalog;

    public ReportDatasourceController(DatasourceCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/datasource")
    public ResponseEntity<DatasourceCatalog.DatasourceView> datasource() {
        return ResponseEntity.ok(catalog.view());
    }
}
