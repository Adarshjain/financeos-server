package com.financeos.domain.account;

import com.financeos.api.account.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public Account createAccount(CreateAccountRequest request) {
        Account account = new Account(request.name(), request.type());
        account.setExcludeFromNetAsset(request.excludeFromNetAsset() != null ? request.excludeFromNetAsset() : false);
        account.setFinancialPosition(request.financialPosition());
        account.setDescription(request.description());
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return accountRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Account getAccountById(UUID id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    public Account addBankDetails(UUID accountId, BankDetailsRequest request) {
        Account account = getAccountById(accountId);
        
        if (account.getType() != AccountType.bank_account) {
            throw new ValidationException("Bank details can only be added to bank accounts");
        }
        
        if (account.getBankDetails() != null) {
            // Update existing
            account.getBankDetails().setOpeningBalance(request.openingBalance());
            account.getBankDetails().setLast4(request.last4());
        } else {
            AccountBankDetails details = new AccountBankDetails(account, request.openingBalance(), request.last4());
            account.setBankDetails(details);
        }
        
        return accountRepository.save(account);
    }

    public Account addCreditCardDetails(UUID accountId, CreditCardDetailsRequest request) {
        Account account = getAccountById(accountId);
        
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
                    request.statementPassword()
            );
            account.setCreditCardDetails(details);
        }
        
        return accountRepository.save(account);
    }

    public Account addStockDetails(UUID accountId, StockDetailsRequest request) {
        Account account = getAccountById(accountId);
        
        if (account.getType() != AccountType.stock) {
            throw new ValidationException("Stock details can only be added to stock accounts");
        }
        
        if (account.getStockDetails() != null) {
            account.getStockDetails().setInstrumentCode(request.instrumentCode());
            account.getStockDetails().setLastTradedPrice(request.lastTradedPrice());
        } else {
            AccountStockDetails details = new AccountStockDetails(account, request.instrumentCode(), request.lastTradedPrice());
            account.setStockDetails(details);
        }
        
        return accountRepository.save(account);
    }

    public Account addMutualFundDetails(UUID accountId, MutualFundDetailsRequest request) {
        Account account = getAccountById(accountId);
        
        if (account.getType() != AccountType.mutual_fund) {
            throw new ValidationException("Mutual fund details can only be added to mutual fund accounts");
        }
        
        if (account.getMutualFundDetails() != null) {
            account.getMutualFundDetails().setInstrumentCode(request.instrumentCode());
            account.getMutualFundDetails().setLastTradedPrice(request.lastTradedPrice());
        } else {
            AccountMutualFundDetails details = new AccountMutualFundDetails(account, request.instrumentCode(), request.lastTradedPrice());
            account.setMutualFundDetails(details);
        }
        
        return accountRepository.save(account);
    }
}

