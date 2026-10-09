package com.financeos.api.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.api.transaction.dto.TransactionResponse;
import com.financeos.api.transactionlink.dto.TransactionLinkSummary;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.domain.account.Account;
import com.financeos.domain.obligation.ObligationRefService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionService;
import com.financeos.domain.transaction.link.TransactionLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

class TransactionControllerGetByIdTest {

    private TransactionService transactionService;
    private TransactionLinkService linkService;
    private ObligationRefService obligationRefService;
    private TransactionController controller;

    @BeforeEach
    void setUp() {
        transactionService = mock(TransactionService.class);
        linkService = mock(TransactionLinkService.class);
        obligationRefService = mock(ObligationRefService.class);
        controller = new TransactionController(transactionService, linkService, obligationRefService);
    }

    @Test
    void returnsTheSameMappingAsASearchRowWithLinksAndRefsAndNoRunningBalance() {
        UUID id = UUID.randomUUID();
        Transaction txn = new Transaction();
        txn.setId(id);
        Account account = new Account();
        account.setId(UUID.randomUUID());
        txn.setAccount(account);
        Map<UUID, List<TransactionLinkSummary>> linkMap = Map.of();
        Map<UUID, List<ObligationRef>> refMap = Map.of();
        when(transactionService.getTransaction(id)).thenReturn(txn);
        when(linkService.linkSummariesFor(List.of(id))).thenReturn(linkMap);
        when(obligationRefService.refsFor(List.of(id))).thenReturn(refMap);

        ResponseEntity<TransactionResponse> response = controller.getTransaction(id);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(TransactionResponse.from(txn, null, linkMap, refMap), response.getBody());
        verify(linkService).linkSummariesFor(List.of(id));
        verify(obligationRefService).refsFor(List.of(id));
    }

    @Test
    void notFoundPropagatesFromTheService() {
        UUID id = UUID.randomUUID();
        when(transactionService.getTransaction(id)).thenThrow(new ResourceNotFoundException("Transaction", id));

        assertThrows(ResourceNotFoundException.class, () -> controller.getTransaction(id));
        verifyNoInteractions(linkService, obligationRefService);
    }
}
