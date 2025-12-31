package com.financeos.api.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * SKELETON CONTROLLER - Dashboard summary endpoints.
 * TODO: Implement aggregated dashboard data
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummary> getSummary() {
        // TODO: Calculate actual summary from transactions and accounts
        return ResponseEntity.ok(new DashboardSummary(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(),
                "skeleton"
        ));
    }

    public record DashboardSummary(
            BigDecimal netWorth,
            BigDecimal totalAssets,
            BigDecimal totalLiabilities,
            BigDecimal monthlyIncome,
            BigDecimal monthlyExpenses,
            List<CategoryBreakdown> categoryBreakdown,
            String status
    ) {}

    public record CategoryBreakdown(
            String category,
            BigDecimal amount,
            BigDecimal percentage
    ) {}
}

