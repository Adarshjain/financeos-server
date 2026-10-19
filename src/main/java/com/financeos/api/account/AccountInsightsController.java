package com.financeos.api.account;

import com.financeos.api.account.dto.AccountFlowSummaryResponse;
import com.financeos.api.account.dto.AccountGmailSummaryResponse;
import com.financeos.api.account.dto.AccountPaymentsFromResponse;
import com.financeos.api.account.dto.AccountTabCountsResponse;
import com.financeos.domain.account.AccountInsightsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Small read endpoints behind the account page's tab counts and Overview blocks. 404 for a missing or another user's account. */
@RestController
@RequestMapping("/api/v1/accounts/{id}")
public class AccountInsightsController {

    private final AccountInsightsService insightsService;

    public AccountInsightsController(AccountInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping("/tab-counts")
    public ResponseEntity<AccountTabCountsResponse> getTabCounts(@PathVariable UUID id) {
        return ResponseEntity.ok(insightsService.tabCounts(id));
    }

    /** Money in/out over the last {@code days} days (1-366, default 90) ending today; 400 for days out of range. */
    @GetMapping("/flow-summary")
    public ResponseEntity<AccountFlowSummaryResponse> getFlowSummary(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "90") int days) {
        return ResponseEntity.ok(insightsService.flowSummary(id, days));
    }

    @GetMapping("/payments-from")
    public ResponseEntity<AccountPaymentsFromResponse> getPaymentsFrom(@PathVariable UUID id) {
        return ResponseEntity.ok(insightsService.paymentsFrom(id));
    }

    @GetMapping("/gmail-summary")
    public ResponseEntity<AccountGmailSummaryResponse> getGmailSummary(@PathVariable UUID id) {
        return ResponseEntity.ok(insightsService.gmailSummary(id));
    }
}
