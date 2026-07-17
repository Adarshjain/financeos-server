package com.financeos.api.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.api.account.dto.CardCycleSummaryResponse;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

class AccountControllerTest {

    private AccountService accountService;
    private AccountController accountController;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        accountController = new AccountController(accountService);
    }

    @Test
    void getAllAccounts_returnsOk() {
        when(accountService.getAllAccounts()).thenReturn(List.of());

        ResponseEntity<List<AccountResponse>> response = accountController.getAllAccounts();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        verify(accountService, times(1)).getAllAccounts();
    }

    @Test
    void getCardCycleSummary_returnsOk() {
        UUID accountId = UUID.randomUUID();
        CardCycleSummaryResponse summary = CardCycleSummaryResponse.empty();
        when(accountService.getCardCycleSummary(accountId)).thenReturn(summary);

        ResponseEntity<CardCycleSummaryResponse> response = accountController.getCardCycleSummary(accountId);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(summary, response.getBody());
        verify(accountService, times(1)).getCardCycleSummary(accountId);
    }
}
