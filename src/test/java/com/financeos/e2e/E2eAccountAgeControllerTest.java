package com.financeos.e2e;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class E2eAccountAgeControllerTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final E2eAccountAgeController controller = new E2eAccountAgeController(accounts);

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    private Account accountOf(UUID ownerId, Instant createdAt) {
        User owner = new User();
        owner.setId(ownerId);
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setUser(owner);
        account.setCreatedAt(createdAt);
        return account;
    }

    @Test
    void movesCreatedAtBackByDaysForTheOwner() {
        UUID me = UUID.randomUUID();
        UserContext.setCurrentUserId(me);
        Instant created = Instant.parse("2026-11-01T10:00:00Z");
        Account account = accountOf(me, created);
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));

        controller.age(account.getId(), new E2eAccountAgeController.AgeRequest(100));

        assertEquals(Instant.parse("2026-07-24T10:00:00Z"), account.getCreatedAt());
        verify(accounts).save(account);
    }

    @Test
    void anotherUsersAccountIsNotFound() {
        UserContext.setCurrentUserId(UUID.randomUUID());
        Account account = accountOf(UUID.randomUUID(), Instant.now());
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.age(account.getId(), new E2eAccountAgeController.AgeRequest(5)));
        assertEquals(404, e.getStatusCode().value());
        verify(accounts, never()).save(any());
    }

    @Test
    void negativeDaysAreRejected() {
        UserContext.setCurrentUserId(UUID.randomUUID());
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> controller.age(UUID.randomUUID(), new E2eAccountAgeController.AgeRequest(-1)));
        assertEquals(400, e.getStatusCode().value());
    }
}
