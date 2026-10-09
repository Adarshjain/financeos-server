package com.financeos.domain.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class TransactionServiceGetByIdTest {

    private TransactionRepository transactionRepository;
    private TransactionService transactionService;
    private UUID userId;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        transactionService = new TransactionService(transactionRepository, mock(AccountRepository.class),
                mock(CategoryRepository.class), mock(UserRepository.class), mock(ReviewStatusManager.class),
                mock(CategorizationService.class), null);
        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void returnsTheTransactionFoundForTheCurrentUser() {
        UUID id = UUID.randomUUID();
        Transaction txn = new Transaction();
        txn.setId(id);
        when(transactionRepository.findAllByIdInAndUserId(List.of(id), userId)).thenReturn(List.of(txn));

        assertSame(txn, transactionService.getTransaction(id));
    }

    @Test
    void unknownOrForeignIdIsNotFound() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findAllByIdInAndUserId(List.of(id), userId)).thenReturn(List.of());

        assertThrows(ResourceNotFoundException.class, () -> transactionService.getTransaction(id));
        verify(transactionRepository, never()).findById(any());
    }
}
