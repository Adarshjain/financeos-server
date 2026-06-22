package com.financeos.api.dashboard;

import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.dashboard.dto.DashboardSummaryResponse;
import com.financeos.api.dashboard.dto.UpdateDashboardRequest;
import com.financeos.domain.dashboard.DashboardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for composable dashboards — grids of report widgets. Widget data itself is fetched
 * per-widget by the client via {@code POST /api/v1/reports/{id}/data}.
 */
@RestController
@RequestMapping("/api/v1/dashboards")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @PostMapping
    public ResponseEntity<DashboardResponse> createDashboard(@Valid @RequestBody CreateDashboardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dashboardService.create(request));
    }

    @GetMapping
    public ResponseEntity<List<DashboardSummaryResponse>> listDashboards() {
        return ResponseEntity.ok(dashboardService.list());
    }

    /** The current user's default dashboard (no id needed); 404 if none is set. */
    @GetMapping("/default")
    public ResponseEntity<DashboardResponse> getDefaultDashboard() {
        return ResponseEntity.ok(dashboardService.getDefault());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DashboardResponse> getDashboard(@PathVariable UUID id) {
        return ResponseEntity.ok(dashboardService.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DashboardResponse> updateDashboard(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDashboardRequest request) {
        return ResponseEntity.ok(dashboardService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDashboard(@PathVariable UUID id) {
        dashboardService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
