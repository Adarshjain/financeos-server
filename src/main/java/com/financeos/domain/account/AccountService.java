package com.financeos.domain.account;

import com.financeos.api.account.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountService(AccountRepository accountRepository,
            UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }
    
    public Account createAccount(CreateAccountRequest request) {
        UUID userId = UserContext.getCurrentUserId();
        User user = userRepository.getReferenceById(userId);

        Account account = new Account(request.name(), request.type());
        account.setUser(user);
        account.setExcludeFromNetAsset(request.excludeFromNetAsset() != null ? request.excludeFromNetAsset() : false);
        account.setFinancialPosition(request.financialPosition());
        account.setDescription(request.description());

        switch (request) {
            case CreateAccountRequest.BankAccountRequest bankReq -> {
                AccountBankDetails details = new AccountBankDetails(
                        account,
                        bankReq.openingBalance(),
                        bankReq.last4());
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

    public Account updateAccount(UUID id, CreateAccountRequest request) {
        Account account = getAccountById(id);

        if (account.getType() != request.type()) {
            throw new ValidationException("Changing account type is not supported");
        }

        account.setName(request.name());
        account.setExcludeFromNetAsset(request.excludeFromNetAsset() != null ? request.excludeFromNetAsset() : false);
        account.setFinancialPosition(request.financialPosition());
        account.setDescription(request.description());

        switch (request) {
            case CreateAccountRequest.BankAccountRequest bankReq -> account = addBankDetails(account, bankReq);
            case CreateAccountRequest.CreditCardRequest ccReq -> account = addCreditCardDetails(account, ccReq);
            case CreateAccountRequest.StockRequest stockReq -> account = addStockDetails(account, stockReq);
            case CreateAccountRequest.MutualFundRequest mfReq -> account = addMutualFundDetails(account, mfReq);
            case CreateAccountRequest.GenericAccountRequest genericReq -> {
                // No extra details to update
            }
        }

        return accountRepository.save(account);
    }

    public Account addBankDetails(Account account, CreateAccountRequest.BankAccountRequest request) {
        if (account.getType() != AccountType.bank_account) {
            throw new ValidationException("Bank details can only be added to bank accounts");
        }

        if (account.getBankDetails() != null) {
            // Update existing
            account.getBankDetails().setOpeningBalance(request.openingBalance());
            account.getBankDetails().setLast4(request.last4());
        } else {
            AccountBankDetails details = new AccountBankDetails(account, request.openingBalance(), request.last4());
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
        accountRepository.deleteById(id);
    }
}
