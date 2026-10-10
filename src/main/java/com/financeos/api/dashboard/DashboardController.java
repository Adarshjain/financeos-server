package com.financeos.api.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.financeos.api.dashboard.dto.BuiltinDataRequest;
import com.financeos.api.dashboard.dto.BuiltinDefinitionResponse;
import com.financeos.api.dashboard.dto.BuiltinParamResponse;
import com.financeos.api.dashboard.dto.BuiltinWidgetResponse;
import com.financeos.api.dashboard.dto.CreateDashboardRequest;
import com.financeos.api.dashboard.dto.DashboardResponse;
import com.financeos.api.dashboard.dto.UpdateDashboardRequest;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.dashboard.BuiltinAvailability;
import com.financeos.domain.dashboard.BuiltinAvailabilityService;
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
    private final BuiltinAvailabilityService availability;

    public DashboardController(DashboardService dashboardService, HomeDashboardSeeder homeSeeder,
            BuiltinWidgetRegistry builtins, ReportDataService reportDataService,
            BuiltinAvailabilityService availability) {
        this.dashboardService = dashboardService;
        this.homeSeeder = homeSeeder;
        this.builtins = builtins;
        this.reportDataService = reportDataService;
        this.availability = availability;
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

    /**
     * The built-in widget catalog: what the editor can place, with each entry's param schema and,
     * per the current user, why an entry cannot be used yet ({@code unavailableReason}).
     */
    @GetMapping("/api/v1/dashboards/builtins")
    public ResponseEntity<List<BuiltinWidgetResponse>> listBuiltins() {
        BuiltinAvailability.Facts facts = availability.factsFor(requireCurrentUserId());
        return ResponseEntity.ok(builtins.all().stream()
                .map(entry -> toResponse(entry, availability.unavailableReason(entry, facts)))
                .toList());
    }

    /**
     * Run a template built-in with the widget's params and return its data (the same union as
     * {@code POST /reports/{id}/data}, with the same paging and sort). 400 for a component built-in.
     */
    @PostMapping("/api/v1/dashboards/builtins/{key}/data")
    public ResponseEntity<ReportData> runBuiltin(
            @PathVariable String key,
            @RequestBody(required = false) BuiltinDataRequest request,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        requireCurrentUserId();
        BuiltinWidgetRegistry.Entry entry = builtins.require(key);
        JsonNode params = request == null ? null : request.params();
        JsonNode definition = builtins.resolveDefinition(entry, params);
        return ResponseEntity.ok(reportDataService.runDefinition(
                entry.templateType(), entry.datasource(), definition, page, size, sort));
    }

    /**
     * The definition a template built-in runs for these params — the params and the request-time
     * filters (today's reward windows, the card filter, the months/days horizon) applied exactly as
     * {@code POST /builtins/{key}/data} applies them — for saving as the caller's own report. Date
     * windows that a relative preset expresses stay relative; today's reward windows are pinned as
     * fixed dates ({@code windowAsOf} = today). 400 for a component built-in or invalid params.
     */
    @PostMapping("/api/v1/dashboards/builtins/{key}/definition")
    public ResponseEntity<BuiltinDefinitionResponse> resolveBuiltinDefinition(
            @PathVariable String key,
            @RequestBody(required = false) BuiltinDataRequest request) {
        requireCurrentUserId();
        BuiltinWidgetRegistry.Entry entry = builtins.require(key);
        JsonNode params = request == null ? null : request.params();
        JsonNode definition = builtins.resolveDefinition(entry, params);
        return ResponseEntity.ok(new BuiltinDefinitionResponse(entry.key(), entry.label(),
                entry.templateType().name(), entry.datasource(), definition,
                BuiltinWidgetRegistry.pinsTodaysWindow(entry) ? AppTime.today() : null));
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

    private BuiltinWidgetResponse toResponse(BuiltinWidgetRegistry.Entry entry, String unavailableReason) {
        JsonNode templateWithDefaults = entry.isTemplate() ? builtins.resolveDefinition(entry, null) : null;
        List<BuiltinParamResponse> params = entry.params().stream()
                .map(p -> new BuiltinParamResponse(p.name(), p.type(), p.required(), p.defaultValue(), p.min(),
                        p.max(), p.ref(), p.options(), p.maxItems(), p.itemPattern()))
                .toList();
        return new BuiltinWidgetResponse(entry.key(), entry.label(), entry.description(), entry.category(),
                entry.subtitle(), entry.requires(), entry.view(), unavailableReason, entry.minW(), entry.kind(),
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
