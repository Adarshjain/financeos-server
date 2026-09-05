package com.financeos.api.account;

import com.financeos.api.account.dto.*;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountIdentifier;
import com.financeos.domain.account.AccountIdentifierService;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final AuthService authService;
    private final AccountIdentifierService accountIdentifierService;

    public AccountController(AccountService accountService,
                             AccountRepository accountRepository,
                             AuthService authService,
                             AccountIdentifierService accountIdentifierService) {
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.authService = authService;
        this.accountIdentifierService = accountIdentifierService;
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

    @GetMapping("/{id}/card-summary")
    public ResponseEntity<CardCycleSummaryResponse> getCardCycleSummary(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.getCardCycleSummary(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AccountResponse> updateAccount(
            @PathVariable UUID id,
            @Valid @RequestBody CreateAccountRequest request) {
        Account account = accountService.updateAccount(id, request);
        return ResponseEntity.ok(AccountResponse.from(account));
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<AccountResponse> closeAccount(
            @PathVariable UUID id,
            @RequestBody(required = false) CloseAccountRequest request) {
        LocalDate closedOn = request != null ? request.closedOn() : null;
        return ResponseEntity.ok(accountService.closeAccount(id, closedOn));
    }

    @PostMapping("/{id}/reopen")
    public ResponseEntity<AccountResponse> reopenAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.reopenAccount(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable UUID id) {
        accountService.deleteAccount(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/identifiers")
    public ResponseEntity<List<AccountIdentifierResponse>> getAccountIdentifiers(@PathVariable UUID id) {
        User currentUser = authService.getCurrentUser();
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
        if (!account.getUser().getId().equals(currentUser.getId())) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        List<AccountIdentifier> identifiers = accountIdentifierService.getIdentifiers(currentUser.getId(), id);
        return ResponseEntity.ok(identifiers.stream().map(AccountIdentifierResponse::from).toList());
    }

    @PostMapping("/{id}/identifiers")
    public ResponseEntity<AccountIdentifierResponse> createAccountIdentifier(
            @PathVariable UUID id,
            @Valid @RequestBody CreateAccountIdentifierRequest request) {
        User currentUser = authService.getCurrentUser();
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
        if (!account.getUser().getId().equals(currentUser.getId())) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        AccountIdentifierService.CreationResult result = accountIdentifierService.createOrUpdateIdentifier(
                currentUser, account, request.value(), request.kind()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountIdentifierResponse.from(result.identifier()));
    }

    @DeleteMapping("/{id}/identifiers/{identifierId}")
    public ResponseEntity<Void> deleteAccountIdentifier(
            @PathVariable UUID id,
            @PathVariable UUID identifierId) {
        User currentUser = authService.getCurrentUser();
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
        if (!account.getUser().getId().equals(currentUser.getId())) {
            throw new ValidationException("You do not have permission to access this account.");
        }
        accountIdentifierService.deleteIdentifier(currentUser.getId(), id, identifierId);
        return ResponseEntity.noContent().build();
    }
}
