package com.financeos.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.user.BirthDateChangedEvent;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Made-up values: old birth date 1990-01-15, new 1991-02-03, last 6 654321. */
class CardPasswordOnBirthDateChangeTest {

    private static final String OLD_DERIVED = "150190654321";
    private static final String NEW_DERIVED = "030291654321";

    private final UUID userId = UUID.randomUUID();
    private AccountRepository accounts;
    private ApplicationEventPublisher events;
    private CardPasswordOnBirthDateChange listener;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        events = mock(ApplicationEventPublisher.class);
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setId(userId);
        user.setDisplayName("Riya Sharma");
        user.setBirthDate("1991-02-03");
        when(users.findById(userId)).thenReturn(Optional.of(user));
        listener = new CardPasswordOnBirthDateChange(users, accounts, events);
    }

    private Account card(String password, String last6) {
        Account a = new Account("Card", AccountType.credit_card);
        a.setId(UUID.randomUUID());
        a.setBank("hsbc");
        a.setCreditCardDetails(new AccountCreditCardDetails(a, BigDecimal.TEN, password));
        a.getCreditCardDetails().setLast6(last6);
        when(accounts.findByUserIdAndType(userId, AccountType.credit_card)).thenReturn(List.of(a));
        return a;
    }

    private void change() {
        listener.onBirthDateChanged(new BirthDateChangedEvent(userId, "1990-01-15"));
    }

    @Test
    void blankPasswordIsSet() {
        Account a = card(null, "654321");
        change();
        assertThat(a.getCreditCardDetails().getStatementPassword()).isEqualTo(NEW_DERIVED);
        verify(events).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void passwordDerivedFromTheOldDateIsReplaced() {
        Account a = card(OLD_DERIVED, "654321");
        change();
        assertThat(a.getCreditCardDetails().getStatementPassword()).isEqualTo(NEW_DERIVED);
        verify(events).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void handTypedPasswordIsUntouched() {
        Account a = card("typed-secret", "654321");
        change();
        assertThat(a.getCreditCardDetails().getStatementPassword()).isEqualTo("typed-secret");
        verify(events, never()).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void cardWithoutLast6IsLeftAlone() {
        Account a = card(null, null);
        change();
        assertThat(a.getCreditCardDetails().getStatementPassword()).isNull();
        verify(events, never()).publishEvent(any(StatementPasswordSavedEvent.class));
    }
}
