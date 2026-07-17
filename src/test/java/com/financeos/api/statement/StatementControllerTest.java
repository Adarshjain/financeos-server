package com.financeos.api.statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.statement.dto.StatementDetailResponse;
import com.financeos.api.statement.dto.StatementSummaryResponse;
import com.financeos.domain.statement.StatementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

class StatementControllerTest {

    private StatementService statementService;
    private StatementController statementController;

    @BeforeEach
    void setUp() {
        statementService = mock(StatementService.class);
        statementController = new StatementController(statementService);
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
        StatementDetailResponse detail = mock(StatementDetailResponse.class);
        when(statementService.getStatementById(statementId)).thenReturn(detail);

        ResponseEntity<StatementDetailResponse> response = statementController.getStatementById(statementId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(detail, response.getBody());
        verify(statementService, times(1)).getStatementById(statementId);
    }
}
