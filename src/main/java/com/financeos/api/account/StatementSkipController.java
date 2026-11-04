package com.financeos.api.account;

import com.financeos.api.account.dto.StatementSkipRequest;
import com.financeos.api.account.dto.StatementSkipResponse;
import com.financeos.api.account.dto.StatementSkipsResponse;
import com.financeos.domain.statement.skip.StatementSkipService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** "No statement this period" for a credit card's billing cycles (Statements tab, Inbox and Upcoming rows). */
@RestController
@RequestMapping("/api/v1/accounts/{accountId}/statement-skips")
public class StatementSkipController {

    private final StatementSkipService service;

    public StatementSkipController(StatementSkipService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<StatementSkipsResponse> state(@PathVariable UUID accountId) {
        return ResponseEntity.ok(service.state(accountId));
    }

    @PostMapping
    public ResponseEntity<StatementSkipResponse> skip(@PathVariable UUID accountId, @Valid @RequestBody StatementSkipRequest request) {
        return ResponseEntity.ok(service.skip(accountId, request.periodEnd()));
    }

    @DeleteMapping("/{skipId}")
    public ResponseEntity<Void> undo(@PathVariable UUID accountId, @PathVariable UUID skipId) {
        service.undo(accountId, skipId);
        return ResponseEntity.noContent().build();
    }
}
