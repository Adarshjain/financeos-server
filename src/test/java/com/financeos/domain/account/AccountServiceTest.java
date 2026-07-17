package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

class AccountServiceTest {

    private AccountRepository accountRepository;
    private UserRepository userRepository;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        userRepository = mock(UserRepository.class);
        accountService = new AccountService(accountRepository, userRepository);
        UserContext.clear();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void getAccountById_sameUser_success() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(user);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        Account result = accountService.getAccountById(accountId);
        assertNotNull(result);
        assertEquals(accountId, result.getId());
    }

    @Test
    void getAccountById_differentUser_throwsValidationException() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User otherUser = new User();
        otherUser.setId(otherUserId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(otherUser);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThrows(ValidationException.class, () -> accountService.getAccountById(accountId));
    }

    @Test
    void deleteAccount_sameUser_success() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User user = new User();
        user.setId(userId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(user);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertDoesNotThrow(() -> accountService.deleteAccount(accountId));
        verify(accountRepository, times(1)).delete(account);
    }

    @Test
    void deleteAccount_differentUser_throwsValidationException() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        User otherUser = new User();
        otherUser.setId(otherUserId);

        Account account = new Account();
        account.setId(accountId);
        account.setUser(otherUser);

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        assertThrows(ValidationException.class, () -> accountService.deleteAccount(accountId));
        verify(accountRepository, never()).delete(any());
    }
}
