package com.financeos.api.account;

import com.financeos.api.account.dto.AccountIdentifierResponse;
import com.financeos.api.account.dto.CreateAccountIdentifierRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.account.*;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AccountControllerIdentifierTest {

    private AccountService accountService;
    private AccountRepository accountRepository;
    private AuthService authService;
    private AccountIdentifierService accountIdentifierService;
    private AccountController controller;

    private User currentUser;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        accountRepository = mock(AccountRepository.class);
        authService = mock(AuthService.class);
        accountIdentifierService = mock(AccountIdentifierService.class);

        controller = new AccountController(
                accountService,
                accountRepository,
                authService,
                accountIdentifierService
        );

        currentUser = new User();
        currentUser.setId(UUID.randomUUID());
        when(authService.getCurrentUser()).thenReturn(currentUser);

        testAccount = new Account("HDFC Savings", AccountType.bank_account);
        testAccount.setId(UUID.randomUUID());
        testAccount.setUser(currentUser);
    }

    @Test
    void getAccountIdentifiers_happyPath_returnsList() {
        AccountIdentifier identifier = new AccountIdentifier(
                UUID.randomUUID(), currentUser, testAccount, "1234", AccountIdentifierKind.CUSTOMER_ID, Instant.now()
        );

        when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));
        when(accountIdentifierService.getIdentifiers(currentUser.getId(), testAccount.getId()))
                .thenReturn(List.of(identifier));

        ResponseEntity<List<AccountIdentifierResponse>> response = controller.getAccountIdentifiers(testAccount.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).value()).isEqualTo("1234");
        assertThat(response.getBody().get(0).kind()).isEqualTo(AccountIdentifierKind.CUSTOMER_ID);
    }

    @Test
    void getAccountIdentifiers_otherUserAccount_throwsValidationException() {
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        testAccount.setUser(otherUser);

        when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));

        assertThrows(ValidationException.class, () -> controller.getAccountIdentifiers(testAccount.getId()));
    }

    @Test
    void createAccountIdentifier_happyPath_returnsCreated() {
        AccountIdentifier identifier = new AccountIdentifier(
                UUID.randomUUID(), currentUser, testAccount, "5678", AccountIdentifierKind.CRN, Instant.now()
        );

        when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));
        when(accountIdentifierService.createOrUpdateIdentifier(
                eq(currentUser), eq(testAccount), eq("5678"), eq(AccountIdentifierKind.CRN)
        )).thenReturn(new AccountIdentifierService.CreationResult(identifier, 0, List.of()));

        CreateAccountIdentifierRequest request = new CreateAccountIdentifierRequest("5678", AccountIdentifierKind.CRN);
        ResponseEntity<AccountIdentifierResponse> response = controller.createAccountIdentifier(testAccount.getId(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().value()).isEqualTo("5678");
        assertThat(response.getBody().kind()).isEqualTo(AccountIdentifierKind.CRN);
    }

    @Test
    void deleteAccountIdentifier_happyPath_returnsNoContent() {
        UUID identifierId = UUID.randomUUID();
        when(accountRepository.findById(testAccount.getId())).thenReturn(Optional.of(testAccount));

        ResponseEntity<Void> response = controller.deleteAccountIdentifier(testAccount.getId(), identifierId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(accountIdentifierService).deleteIdentifier(currentUser.getId(), testAccount.getId(), identifierId);
    }
}
