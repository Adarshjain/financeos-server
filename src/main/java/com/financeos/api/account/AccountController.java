package com.financeos.api.account;

import com.financeos.api.account.dto.*;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @GetMapping
    public ResponseEntity<List<AccountResponse>> getAllAccounts() {
        List<Account> accounts = accountService.getAllAccounts();
        List<AccountResponse> response = accounts.stream()
                .map(AccountResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> getAccountById(@PathVariable UUID id) {
        Account account = accountService.getAccountById(id);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @PostMapping("/{id}/bank-details")
    public ResponseEntity<AccountResponse> addBankDetails(
            @PathVariable UUID id,
            @Valid @RequestBody BankDetailsRequest request) {
        Account account = accountService.addBankDetails(id, request);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @PostMapping("/{id}/credit-card-details")
    public ResponseEntity<AccountResponse> addCreditCardDetails(
            @PathVariable UUID id,
            @Valid @RequestBody CreditCardDetailsRequest request) {
        Account account = accountService.addCreditCardDetails(id, request);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @PostMapping("/{id}/stock-details")
    public ResponseEntity<AccountResponse> addStockDetails(
            @PathVariable UUID id,
            @Valid @RequestBody StockDetailsRequest request) {
        Account account = accountService.addStockDetails(id, request);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @PostMapping("/{id}/mutual-fund-details")
    public ResponseEntity<AccountResponse> addMutualFundDetails(
            @PathVariable UUID id,
            @Valid @RequestBody MutualFundDetailsRequest request) {
        Account account = accountService.addMutualFundDetails(id, request);
        return ResponseEntity.ok(AccountResponse.from(account));
    }
}

