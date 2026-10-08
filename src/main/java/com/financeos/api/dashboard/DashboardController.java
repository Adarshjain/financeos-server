package com.financeos.api.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.api.dashboard.dto.BuiltinDataRequest;
import com.financeos.api.dashboard.dto.BuiltinParamResponse;
import com.financeos.api.dashboard.dto.BuiltinWidgetResponse;
import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.dashboard.dto.UpdateDashboardRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry;
import com.financeos.domain.dashboard.DashboardService;
import com.financeos.domain.dashboard.HomeDashboardSeeder;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.engine.ReportData;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * CRUD for composable dashboards — grids of report widgets and built-in widgets — plus the
 * built-in widget catalog and the Home dashboard seeding/restore.
 */
@RestController
public class DashboardController {

    private final DashboardService dashboardService;
    private final HomeDashboardSeeder homeSeeder;
    private final BuiltinWidgetRegistry builtins;
    private final ReportDataService reportDataService;

    public DashboardController(DashboardService dashboardService, HomeDashboardSeeder homeSeeder,
            BuiltinWidgetRegistry builtins, ReportDataService reportDataService) {
        this.dashboardService = dashboardService;
        this.homeSeeder = homeSeeder;
        this.builtins = builtins;
        this.reportDataService = reportDataService;
    }

    private UUID requireCurrentUserId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @PostMapping("/api/v1/dashboards")
    public ResponseEntity<DashboardResponse> createDashboard(@Valid @RequestBody CreateDashboardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dashboardService.create(request));
    }

    /** Seeds the Home dashboard on a user's first visit (own transaction) before the read-only list. */
    @GetMapping("/api/v1/dashboards")
    public ResponseEntity<List<DashboardResponse>> listDashboards() {
        homeSeeder.seedIfNeeded(requireCurrentUserId());
        return ResponseEntity.ok(dashboardService.list());
    }

    /** The current user's default dashboard (seeding Home first if never seeded); 404 if none is set. */
    @GetMapping("/api/v1/dashboards/default")
    public ResponseEntity<DashboardResponse> getDefaultDashboard() {
        homeSeeder.seedIfNeeded(requireCurrentUserId());
        return ResponseEntity.ok(dashboardService.getDefault());
    }

    /** The built-in widget catalog: what the editor can place, with each entry's param schema. */
    @GetMapping("/api/v1/dashboards/builtins")
    public ResponseEntity<List<BuiltinWidgetResponse>> listBuiltins() {
        return ResponseEntity.ok(builtins.all().stream().map(this::toResponse).toList());
    }

    /**
     * Run a template built-in with the widget's params and return its data (the same union as
     * {@code POST /reports/{id}/data}). 400 for a component built-in.
     */
    @PostMapping("/api/v1/dashboards/builtins/{key}/data")
    public ResponseEntity<ReportData> runBuiltin(
            @PathVariable String key,
            @RequestBody(required = false) BuiltinDataRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireCurrentUserId();
        BuiltinWidgetRegistry.Entry entry = builtins.require(key);
        JsonNode params = request == null ? null : request.params();
        JsonNode definition = builtins.resolveDefinition(entry, params);
        return ResponseEntity.ok(reportDataService.runDefinition(
                entry.templateType(), entry.datasource(), definition, page, size));
    }

    /** Creates a fresh Home dashboard (the seeded layout) and makes it the default. */
    @PostMapping("/api/v1/dashboards/home/restore")
    public ResponseEntity<DashboardResponse> restoreHome() {
        return ResponseEntity.status(HttpStatus.CREATED).body(homeSeeder.restore(requireCurrentUserId()));
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

    private BuiltinWidgetResponse toResponse(BuiltinWidgetRegistry.Entry entry) {
        JsonNode templateWithDefaults = entry.isTemplate() ? builtins.resolveDefinition(entry, null) : null;
        List<BuiltinParamResponse> params = entry.params().stream()
                .map(p -> new BuiltinParamResponse(p.name(), p.type(), p.required(), p.defaultValue(), p.min(), p.max()))
                .toList();
        return new BuiltinWidgetResponse(entry.key(), entry.label(), entry.description(), entry.minW(), entry.kind(),
                entry.templateType() == null ? null : entry.templateType().name(), entry.datasource(),
                templateWithDefaults, entry.href(), params);
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
