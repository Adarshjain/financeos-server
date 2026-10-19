package com.financeos.domain.statement;

import com.financeos.api.statement.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class StatementService {

    private static final int IN_CHUNK = 500;
    private static final BigDecimal MATCH_TOLERANCE = new BigDecimal("0.01");

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

        List<StatementLineProjection> projections = statementTransactionRepository.findLinesByStatementId(statementId);
        // A line is "created" under the rule a delete uses: the statement made the transaction and no other statement has a line for it.
        Set<UUID> elsewhere = new HashSet<>();
        List<UUID> lineIds = projections.stream().map(StatementLineProjection::transactionId).toList();
        for (int i = 0; i < lineIds.size(); i += IN_CHUNK) {
            elsewhere.addAll(statementTransactionRepository.findLinkedToOtherStatements(
                    lineIds.subList(i, Math.min(i + IN_CHUNK, lineIds.size())), statementId));
        }
        List<StatementLineResponse> lines = projections.stream()
                .map(p -> StatementLineResponse.from(p,
                        !elsewhere.contains(p.transactionId())
                                && statement.createdTransaction(p.source(), p.sourceMessageId())))
                .toList();

        BigDecimal ledgerBalanceAtClose = ledgerBalanceAtClose(statement);
        Boolean matchesLedger = ledgerBalanceAtClose == null
                ? null
                : ledgerBalanceAtClose.subtract(statement.getClosingBalance()).abs().compareTo(MATCH_TOLERANCE) < 0;

        return StatementDetailResponse.from(statement, cardDetails, lines, ledgerBalanceAtClose, matchesLedger);
    }

    /**
     * What the account's own transactions say the balance was at the end of the statement's last day,
     * in the sign of the statement's closing balance. Null when the statement has no closing balance
     * or period end, or the account is not a bank account or credit card.
     */
    @Nullable
    private BigDecimal ledgerBalanceAtClose(Statement statement) {
        Account account = statement.getAccount();
        if (statement.getClosingBalance() == null || statement.getPeriodEnd() == null || account == null
                || (account.getType() != AccountType.bank_account && account.getType() != AccountType.credit_card)) {
            return null;
        }
        BigDecimal balance = accountService.transactionBalanceAsOf(account, statement.getPeriodEnd());
        // A card statement states the amount owed; the account balance is negative when money is owed (BalanceMath.apply).
        return account.getType() == AccountType.credit_card ? balance.negate() : balance;
    }
}
