package com.financeos.api.statement;

import com.financeos.api.statement.dto.StatementDeletionPreviewResponse;
import com.financeos.api.statement.dto.StatementDeletionResponse;
import com.financeos.api.statement.dto.StatementDetailResponse;
import com.financeos.api.statement.dto.StatementSummaryResponse;
import com.financeos.domain.statement.StatementDeletionService;
import com.financeos.domain.statement.StatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class StatementController {

    private final StatementService statementService;
    private final StatementDeletionService statementDeletionService;

    public StatementController(StatementService statementService, StatementDeletionService statementDeletionService) {
        this.statementService = statementService;
        this.statementDeletionService = statementDeletionService;
    }

    @GetMapping("/accounts/{accountId}/statements")
    public ResponseEntity<List<StatementSummaryResponse>> getStatementsByAccountId(@PathVariable UUID accountId) {
        return ResponseEntity.ok(statementService.getStatementsByAccountId(accountId));
    }

    @GetMapping("/statements/{statementId}")
    public ResponseEntity<StatementDetailResponse> getStatementById(@PathVariable UUID statementId) {
        return ResponseEntity.ok(statementService.getStatementById(statementId));
    }

    /** What {@link #deleteStatement} would remove and keep, for the confirmation step. */
    @GetMapping("/accounts/{accountId}/statements/{statementId}/deletion-preview")
    public ResponseEntity<StatementDeletionPreviewResponse> previewStatementDeletion(@PathVariable UUID accountId,
                                                                                     @PathVariable UUID statementId) {
        return ResponseEntity.ok(statementDeletionService.preview(accountId, statementId));
    }

    /** Deletes the statement and the transactions it created, so the file can be ingested again. */
    @DeleteMapping("/accounts/{accountId}/statements/{statementId}")
    public ResponseEntity<StatementDeletionResponse> deleteStatement(@PathVariable UUID accountId,
                                                                     @PathVariable UUID statementId) {
        return ResponseEntity.ok(statementDeletionService.delete(accountId, statementId));
    }
}
