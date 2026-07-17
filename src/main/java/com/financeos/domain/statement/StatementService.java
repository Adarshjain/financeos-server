package com.financeos.domain.statement;

import com.financeos.api.statement.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class StatementService {

    private final StatementRepository statementRepository;
    private final StatementTransactionRepository statementTransactionRepository;
    private final AccountService accountService;

    public StatementService(StatementRepository statementRepository,
                            StatementTransactionRepository statementTransactionRepository,
                            AccountService accountService) {
        this.statementRepository = statementRepository;
        this.statementTransactionRepository = statementTransactionRepository;
        this.accountService = accountService;
    }

    public List<StatementSummaryResponse> getStatementsByAccountId(UUID accountId) {
        accountService.getAccountById(accountId);

        List<Statement> statements = statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(accountId);
        return statements.stream()
                .map(StatementSummaryResponse::from)
                .toList();
    }

    public StatementDetailResponse getStatementById(UUID statementId) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new ResourceNotFoundException("Statement", statementId));

        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null && !statement.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this statement.");
        }

        StatementCardDetailsResponse cardDetails = null;
        if ("credit_card".equals(statement.getStatementType()) && statement.getCreditCardDetails() != null) {
            cardDetails = StatementCardDetailsResponse.from(statement.getCreditCardDetails());
        }

        List<StatementLineResponse> lines = statementTransactionRepository.findLinesByStatementId(statementId);

        return StatementDetailResponse.from(statement, cardDetails, lines);
    }
}
