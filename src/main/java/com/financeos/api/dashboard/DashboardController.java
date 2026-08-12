package com.financeos.api.dashboard;

import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.dashboard.dto.UpdateDashboardRequest;
import com.financeos.domain.dashboard.DashboardService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for composable dashboards — grids of report widgets and summary figures.
 */
@RestController
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @PostMapping("/api/v1/dashboards")
    public ResponseEntity<DashboardResponse> createDashboard(@Valid @RequestBody CreateDashboardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dashboardService.create(request));
    }

    @GetMapping("/api/v1/dashboards")
    public ResponseEntity<List<DashboardResponse>> listDashboards() {
        return ResponseEntity.ok(dashboardService.list());
    }

    /** The current user's default dashboard (no id needed); 404 if none is set. */
    @GetMapping("/api/v1/dashboards/default")
    public ResponseEntity<DashboardResponse> getDefaultDashboard() {
        return ResponseEntity.ok(dashboardService.getDefault());
    }

    @GetMapping("/api/v1/dashboards/{id}")
    public ResponseEntity<DashboardResponse> getDashboard(@PathVariable UUID id) {
        return ResponseEntity.ok(dashboardService.get(id));
    }

    @PutMapping("/api/v1/dashboards/{id}")
    public ResponseEntity<DashboardResponse> updateDashboard(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDashboardRequest request) {
        return ResponseEntity.ok(dashboardService.update(id, request));
    }

    @DeleteMapping("/api/v1/dashboards/{id}")
    public ResponseEntity<Void> deleteDashboard(@PathVariable UUID id) {
        dashboardService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/dashboard/summary")
    public ResponseEntity<DashboardSummary> getSummary() {
        return ResponseEntity.ok(new DashboardSummary(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                "skeleton"));
    }

    public record DashboardSummary(
            BigDecimal netWorth,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal monthlyIncome,
            BigDecimal monthlyExpenses,
            List<CategoryBreakdown> categoryBreakdown,
            String status) {
    }

    public record CategoryBreakdown(
            String category,
            BigDecimal amount,
            BigDecimal percentage) {
    }
}
