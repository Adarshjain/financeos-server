package com.financeos.api.account;

import com.financeos.api.account.dto.BalancePointResponse;
import com.financeos.domain.account.AccountBalanceSeriesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** An account's daily balance trend (the account tile's sparkline). */
@RestController
public class AccountBalanceSeriesController {

    private final AccountBalanceSeriesService balanceSeriesService;

    public AccountBalanceSeriesController(AccountBalanceSeriesService balanceSeriesService) {
        this.balanceSeriesService = balanceSeriesService;
    }

    /**
     * End-of-day balances for the last {@code days} days (1-365, default 30) ending today, oldest
     * first; an empty list for a broker. 400 for days out of range, 404 for a missing or another
     * user's account.
     */
    @GetMapping("/api/v1/accounts/{id}/balance-series")
    public ResponseEntity<List<BalancePointResponse>> getBalanceSeries(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(balanceSeriesService.series(id, days));
    }
}
