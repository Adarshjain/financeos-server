package com.financeos.api.statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.statement.dto.StatementDeletionPreviewResponse;
import com.financeos.api.statement.dto.StatementDeletionResponse;
import com.financeos.api.statement.dto.StatementDetailResponse;
import com.financeos.api.statement.dto.StatementSummaryResponse;
import com.financeos.domain.statement.StatementDeletionService;
import com.financeos.domain.statement.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

class StatementControllerTest {

    private StatementService statementService;
    private StatementDeletionService statementDeletionService;
    private StatementController statementController;

    @BeforeEach
    void setUp() {
        statementService = mock(StatementService.class);
        statementDeletionService = mock(StatementDeletionService.class);
        statementController = new StatementController(statementService, statementDeletionService);
    }

    @Test
    void getStatementsByAccountId_returnsOk() {
        UUID accountId = UUID.randomUUID();
        when(statementService.getStatementsByAccountId(accountId)).thenReturn(List.of());

        ResponseEntity<List<StatementSummaryResponse>> response = statementController.getStatementsByAccountId(accountId);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        verify(statementService, times(1)).getStatementsByAccountId(accountId);
    }

    @Test
    void getStatementById_returnsOk() {
        UUID statementId = UUID.randomUUID();
        StatementDetailResponse detail = new StatementDetailResponse(
                statementId, null, "ref", "BANK", null, null,
                null, null, null, null, 0, 0, "PDF",
                null, true, null, "HDFC", "1234", null, null, List.of()
        );
        when(statementService.getStatementById(statementId)).thenReturn(detail);

        ResponseEntity<StatementDetailResponse> response = statementController.getStatementById(statementId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(detail, response.getBody());
        verify(statementService, times(1)).getStatementById(statementId);
    }

    @Test
    void previewStatementDeletion_returnsTheServicesCounts() {
        UUID accountId = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        StatementDeletionPreviewResponse preview = new StatementDeletionPreviewResponse(5, 1, 0, 1);
        when(statementDeletionService.preview(accountId, statementId)).thenReturn(preview);

        ResponseEntity<StatementDeletionPreviewResponse> response = statementController.previewStatementDeletion(accountId, statementId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(preview, response.getBody());
    }

    @Test
    void deleteStatement_returnsTheOutcome() {
        UUID accountId = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        StatementDeletionResponse outcome = new StatementDeletionResponse(5, 1, 1, 0);
        when(statementDeletionService.delete(accountId, statementId)).thenReturn(outcome);

        ResponseEntity<StatementDeletionResponse> response = statementController.deleteStatement(accountId, statementId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(outcome, response.getBody());
    }
}
