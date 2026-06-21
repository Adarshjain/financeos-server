package com.financeos.api.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKELETON CONTROLLER - Fixed financial summary (net worth, assets, liabilities, ...).
 * Distinct from the composable dashboards under {@code /api/v1/dashboards}; these figures need
 * account balances that the report engine's transactions-only datasource cannot yet produce.
 * TODO: Calculate the real summary from transactions and accounts.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardSummaryController {

    @GetMapping("/summary")
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
