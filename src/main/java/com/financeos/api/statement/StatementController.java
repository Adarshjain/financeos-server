package com.financeos.api.statement;

import com.financeos.api.statement.dto.StatementDetailResponse;
import com.financeos.api.statement.dto.StatementSummaryResponse;
import com.financeos.domain.statement.StatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping("/accounts/{accountId}/statements")
    public ResponseEntity<List<StatementSummaryResponse>> getStatementsByAccountId(@PathVariable UUID accountId) {
        return ResponseEntity.ok(statementService.getStatementsByAccountId(accountId));
    }

    @GetMapping("/statements/{statementId}")
    public ResponseEntity<StatementDetailResponse> getStatementById(@PathVariable UUID statementId) {
        return ResponseEntity.ok(statementService.getStatementById(statementId));
    }
}
