package com.financeos.domain.account;

import com.financeos.api.account.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final StatementRepository statementRepository;
    private final TransactionRepository transactionRepository;

    public AccountService(AccountRepository accountRepository,
            UserRepository userRepository,
            StatementRepository statementRepository,
            TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.statementRepository = statementRepository;
        this.transactionRepository = transactionRepository;
    }
    
    public Account createAccount(CreateAccountRequest request) {
        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        Account account = new Account(request.name(), request.type());
        account.setUser(user);
        account.setExcludeFromNetAsset(request.excludeFromNetAsset() != null ? request.excludeFromNetAsset() : false);
        account.setFinancialPosition(request.financialPosition());
        account.setDescription(request.description());
        account.setIngestFromDate(request.ingestFromDate());

        switch (request) {
            case CreateAccountRequest.BankAccountRequest bankReq -> {
                AccountBankDetails details = new AccountBankDetails(
                        account,
                        bankReq.openingBalance(),
                        bankReq.last4(),
                        bankReq.statementPassword());
                details.setUser(user);
                account.setBankDetails(details);
            }
            case CreateAccountRequest.CreditCardRequest ccReq -> {
                account = addCreditCardDetails(account, ccReq);
            }
            case CreateAccountRequest.StockRequest stockReq -> {
                account = addStockDetails(account, stockReq);
            }
            case CreateAccountRequest.MutualFundRequest mfReq -> {
                account = addMutualFundDetails(account, mfReq);
            }
            case CreateAccountRequest.GenericAccountRequest genericReq -> {
                // No extra details to update
            }
        }

        Account saved = accountRepository.save(account);
        populateBalanceInfo(saved);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        List<Account> accounts = accountRepository.findAll();
        accounts.forEach(this::populateBalanceInfo);
        return accounts;
    }

    @Transactional(readOnly = true)
    public Account getAccountById(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
        UUID currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null && !account.getUser().getId().equals(currentUserId)) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        populateBalanceInfo(account);
        return account;
    }

    public Account updateAccount(UUID id, CreateAccountRequest request) {
        Account account = getAccountById(id);

        if (account.getType() != request.type()) {
            throw new ValidationException("Changing account type is not supported");
        }

        account.setName(request.name());
        account.setExcludeFromNetAsset(request.excludeFromNetAsset() != null ? request.excludeFromNetAsset() : false);
        account.setFinancialPosition(request.financialPosition());
        account.setDescription(request.description());
        account.setIngestFromDate(request.ingestFromDate());

        switch (request) {
            case CreateAccountRequest.BankAccountRequest bankReq -> account = addBankDetails(account, bankReq);
            case CreateAccountRequest.CreditCardRequest ccReq -> account = addCreditCardDetails(account, ccReq);
            case CreateAccountRequest.StockRequest stockReq -> account = addStockDetails(account, stockReq);
            case CreateAccountRequest.MutualFundRequest mfReq -> account = addMutualFundDetails(account, mfReq);
            case CreateAccountRequest.GenericAccountRequest genericReq -> {
                // No extra details to update
            }
        }

        Account saved = accountRepository.save(account);
        populateBalanceInfo(saved);
        return saved;
    }

    public Account addBankDetails(Account account, CreateAccountRequest.BankAccountRequest request) {
        if (account.getType() != AccountType.bank_account) {
            throw new ValidationException("Bank details can only be added to bank accounts");
        }

        if (account.getBankDetails() != null) {
            // Update existing
            account.getBankDetails().setOpeningBalance(request.openingBalance());
            account.getBankDetails().setLast4(request.last4());
            account.getBankDetails().setStatementPassword(request.statementPassword());
        } else {
            AccountBankDetails details = new AccountBankDetails(account, request.openingBalance(), request.last4(), request.statementPassword());
            details.setUser(account.getUser());
            account.setBankDetails(details);
        }

        return accountRepository.save(account);
    }

    public Account addCreditCardDetails(Account account, CreateAccountRequest.CreditCardRequest request) {
        if (account.getType() != AccountType.credit_card) {
            throw new ValidationException("Credit card details can only be added to credit card accounts");
        }

        if (account.getCreditCardDetails() != null) {
            // Update existing
            AccountCreditCardDetails details = account.getCreditCardDetails();
            details.setLast4(request.last4());
            details.setCreditLimit(request.creditLimit());
            details.setPaymentDueDay(request.paymentDueDay());
            details.setGracePeriodDays(request.gracePeriodDays());
            details.setStatementPassword(request.statementPassword());
        } else {
            AccountCreditCardDetails details = new AccountCreditCardDetails(
                    account,
                    request.last4(),
                    request.creditLimit(),
                    request.paymentDueDay(),
                    request.gracePeriodDays(),
                    request.statementPassword());
            details.setUser(account.getUser());
            account.setCreditCardDetails(details);
        }

        return accountRepository.save(account);
    }

    public Account addStockDetails(Account account, CreateAccountRequest.StockRequest request) {
        if (account.getType() != AccountType.stock) {
            throw new ValidationException("Stock details can only be added to stock accounts");
        }

        if (account.getStockDetails() != null) {
            account.getStockDetails().setInstrumentCode(request.instrumentCode());
            account.getStockDetails().setLastTradedPrice(request.lastTradedPrice());
        } else {
            AccountStockDetails details = new AccountStockDetails(account, request.instrumentCode(),
                    request.lastTradedPrice());
            details.setUser(account.getUser());
            account.setStockDetails(details);
        }

        return accountRepository.save(account);
    }

    public Account addMutualFundDetails(Account account, CreateAccountRequest.MutualFundRequest request) {
        if (account.getType() != AccountType.mutual_fund) {
            throw new ValidationException("Mutual fund details can only be added to mutual fund accounts");
        }

        if (account.getMutualFundDetails() != null) {
            account.getMutualFundDetails().setInstrumentCode(request.instrumentCode());
            account.getMutualFundDetails().setLastTradedPrice(request.lastTradedPrice());
        } else {
            AccountMutualFundDetails details = new AccountMutualFundDetails(account, request.instrumentCode(),
                    request.lastTradedPrice());
            details.setUser(account.getUser());
            account.setMutualFundDetails(details);
        }

        return accountRepository.save(account);
    }

    public void deleteAccount(UUID id) {
        Account account = getAccountById(id); // Performs ownership check
        accountRepository.delete(account);
    }

    @Transactional(readOnly = true)
    public CardCycleSummaryResponse getCardCycleSummary(UUID accountId) {
        Account account = getAccountById(accountId); // Performs ownership check & 404/400 convention
        if (account.getType() != AccountType.credit_card) {
            throw new ValidationException("Card cycle summary is only available for credit card accounts.");
        }

        List<Statement> statements = statementRepository.findQualifyingCreditCardStatements(accountId);
        if (statements.isEmpty()) {
            return CardCycleSummaryResponse.empty();
        }

        List<CardCycleHistoryItemResponse> history = statements.stream()
                .map(s -> {
                    StatementCreditCardDetails d = s.getCreditCardDetails();
                    return new CardCycleHistoryItemResponse(
                            s.getPeriodEnd(),
                            d != null ? d.getTotalPurchases() : null,
                            d != null ? d.getPaymentsReceived() : null,
                            d != null ? d.getFinanceCharges() : null,
                            d != null ? d.getFeesAndCharges() : null,
                            d != null ? d.getRewardPointsBalance() : null
                    );
                })
                .toList();

        Statement latest = statements.get(statements.size() - 1);
        StatementCreditCardDetails d = latest.getCreditCardDetails();

        Long daysUntilDue = null;
        if (d != null && d.getPaymentDueDate() != null) {
            daysUntilDue = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), d.getPaymentDueDate());
        }

        // The statement's harvested limit wins; fall back to the limit configured
        // on the account when the statement didn't carry one.
        BigDecimal creditLimit = d != null && d.getCreditLimit() != null
                ? d.getCreditLimit()
                : (account.getCreditCardDetails() != null ? account.getCreditCardDetails().getCreditLimit() : null);

        BigDecimal utilizationPct = null;
        if (d != null && creditLimit != null && creditLimit.compareTo(BigDecimal.ZERO) > 0 && d.getTotalAmountDue() != null) {
            utilizationPct = d.getTotalAmountDue()
                    .divide(creditLimit, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }

        return new CardCycleSummaryResponse(
                latest.getId(),
                latest.getPeriodStart(),
                latest.getPeriodEnd(),
                d != null ? d.getTotalAmountDue() : null,
                d != null ? d.getMinimumAmountDue() : null,
                d != null ? d.getPaymentDueDate() : null,
                daysUntilDue,
                creditLimit,
                d != null ? d.getAvailableCreditLimit() : null,
                utilizationPct,
                d != null ? d.getRewardPointsBalance() : null,
                history
        );
    }

    private void populateBalanceInfo(Account account) {
        List<StatementRepository.AnchorStatementProjection> eligible = statementRepository.findEligibleAnchorStatements(account.getId(), org.springframework.data.domain.PageRequest.of(0, 1));
        if (!eligible.isEmpty()) {
            StatementRepository.AnchorStatementProjection anchor = eligible.get(0);
            BigDecimal anchorClosingBalance = anchor.getClosingBalance();
            LocalDate anchorDate = anchor.getPeriodEnd();

            TransactionRepository.BalanceAggregatesProjection aggregates = transactionRepository.findBalanceAggregatesByAccountId(account.getId(), anchorDate);
            BigDecimal postAnchorSum = aggregates != null && aggregates.getPostAnchorSum() != null ? aggregates.getPostAnchorSum() : BigDecimal.ZERO;

            BigDecimal anchoredBalance;
            if (account.getType() == AccountType.credit_card) {
                BigDecimal base = anchorClosingBalance.compareTo(BigDecimal.ZERO) > 0
                        ? anchorClosingBalance.negate()
                        : anchorClosingBalance.abs();
                anchoredBalance = base.add(postAnchorSum);
            } else {
                anchoredBalance = anchorClosingBalance.add(postAnchorSum);
            }

            account.setCalculatedBalance(anchoredBalance);
            account.setBalanceAnchored(true);
            account.setAnchorDate(anchorDate);

            if (account.getType() == AccountType.bank_account && account.getBankDetails() != null && account.getBankDetails().getOpeningBalance() != null) {
                BigDecimal totalSum = aggregates != null && aggregates.getTotalSum() != null ? aggregates.getTotalSum() : BigDecimal.ZERO;
                BigDecimal pureTxBalance = account.getBankDetails().getOpeningBalance().add(totalSum);
                BigDecimal gap = pureTxBalance.subtract(anchoredBalance);
                if (gap.abs().compareTo(new BigDecimal("0.01")) >= 0) {
                    account.setReconciliationGap(gap);
                } else {
                    account.setReconciliationGap(null);
                }
            } else {
                account.setReconciliationGap(null);
            }
        } else {
            account.setBalanceAnchored(false);
            account.setAnchorDate(null);
            account.setReconciliationGap(null);
            BigDecimal totalSum = transactionRepository.findTotalTransactionSumByAccountId(account.getId());
            if (totalSum == null) totalSum = BigDecimal.ZERO;
            if (account.getType() == AccountType.bank_account && account.getBankDetails() != null && account.getBankDetails().getOpeningBalance() != null) {
                account.setCalculatedBalance(account.getBankDetails().getOpeningBalance().add(totalSum));
            } else {
                account.setCalculatedBalance(totalSum);
            }
        }
    }
}
