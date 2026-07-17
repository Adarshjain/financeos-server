package com.financeos.domain.statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.statement.dto.StatementDetailResponse;
import com.financeos.api.statement.dto.StatementSummaryResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class StatementServiceTest {

    private StatementRepository statementRepository;
    private StatementTransactionRepository statementTransactionRepository;
    private AccountService accountService;
    private StatementService statementService;

    @BeforeEach
    void setUp() {
        statementRepository = mock(StatementRepository.class);
        statementTransactionRepository = mock(StatementTransactionRepository.class);
        accountService = mock(AccountService.class);
        statementService = new StatementService(statementRepository, statementTransactionRepository, accountService);
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void getStatementsByAccountId_success() {
        UUID accountId = UUID.randomUUID();
        Statement statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setStatementType("bank_account");
        statement.setPeriodEnd(LocalDate.now());

        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(accountId))
                .thenReturn(List.of(statement));

        List<StatementSummaryResponse> result = statementService.getStatementsByAccountId(accountId);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(statement.getId(), result.get(0).id());
        verify(accountService, times(1)).getAccountById(accountId);
    }

    @Test
    void getStatementById_sameUser_success() {
        UUID userId = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Statement statement = new Statement();
        statement.setId(statementId);
        statement.setUser(user);
        statement.setStatementType("credit_card");

        StatementCreditCardDetails cardDetails = new StatementCreditCardDetails(statement);
        cardDetails.setTotalAmountDue(BigDecimal.valueOf(100));
        statement.setCreditCardDetails(cardDetails);

        when(statementRepository.findById(statementId)).thenReturn(Optional.of(statement));
        when(statementTransactionRepository.findLinesByStatementId(statementId)).thenReturn(List.of());

        StatementDetailResponse result = statementService.getStatementById(statementId);

        assertNotNull(result);
        assertEquals(statementId, result.id());
        assertNotNull(result.cardDetails());
        assertEquals(BigDecimal.valueOf(100), result.cardDetails().totalAmountDue());
    }

    @Test
    void getStatementById_differentUser_throwsValidationException() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID statementId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User otherUser = new User();
        otherUser.setId(otherUserId);

        Statement statement = new Statement();
        statement.setId(statementId);
        statement.setUser(otherUser);

        when(statementRepository.findById(statementId)).thenReturn(Optional.of(statement));

        assertThrows(ValidationException.class, () -> statementService.getStatementById(statementId));
    }

    @Test
    void getStatementById_notFound_throwsResourceNotFoundException() {
        UUID statementId = UUID.randomUUID();
        when(statementRepository.findById(statementId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> statementService.getStatementById(statementId));
    }
}
