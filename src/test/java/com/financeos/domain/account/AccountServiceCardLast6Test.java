package com.financeos.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.api.account.dto.CreateAccountRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Card last 6 on create and update, with made-up values: birth date 1990-01-15, last 6 654321 / 123456, last 4 4321 / 3456. */
class AccountServiceCardLast6Test {

    private static final String DERIVED = "150190654321";

    private AccountRepository accountRepository;
    private AccountService service;
    private ApplicationEventPublisher publisher;
    private User user;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        UserRepository userRepository = mock(UserRepository.class);
        service = new AccountService(accountRepository,
                mock(com.financeos.domain.account.card.CardholderRepository.class),
                mock(com.financeos.domain.account.card.CardRepository.class), userRepository,
                mock(StatementRepository.class), mock(TransactionRepository.class),
                mock(HoldingValuationService.class),
                mock(com.financeos.gmail.domain.GmailBackfillDemandRepository.class),
                publisher);
        UUID userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setDisplayName("Riya Sharma");
        user.setBirthDate("1990-01-15");
        UserContext.setCurrentUserId(userId);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CreateAccountRequest card(String bank, String last4, String password, String last6) {
        return new CreateAccountRequest.CreditCardRequest("Card", AccountType.credit_card, false,
                FinancialPosition.liability, null, last4, new BigDecimal("50000"), LocalDate.of(2026, 1, 1),
                password, null, null, null, null, null, bank, null, null, null, last6);
    }

    private Account stored(String bank, String last4, String password, String last6) {
        Account created = service.createAccount(card(bank, last4, password, last6));
        created.setId(UUID.randomUUID());
        when(accountRepository.findById(created.getId())).thenReturn(Optional.of(created));
        clearInvocations(publisher);
        return created;
    }

    private static String pwd(Account a) {
        return a.getCreditCardDetails().getStatementPassword();
    }

    @Test
    void createHsbcWithLast6AndNoPassword_derivesAndPublishes() {
        Account saved = service.createAccount(card("hsbc", "4321", null, "654321"));
        assertThat(pwd(saved)).isEqualTo(DERIVED);
        assertThat(saved.getCreditCardDetails().getLast6()).isEqualTo("654321");
        verify(publisher).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void explicitPasswordWins() {
        Account saved = service.createAccount(card("hsbc", "4321", "typed-secret", "654321"));
        assertThat(pwd(saved)).isEqualTo("typed-secret");
        assertThat(saved.getCreditCardDetails().getLast6()).isEqualTo("654321");
    }

    @Test
    void badLast6IsRejected() {
        ValidationException notSix = assertThrows(ValidationException.class,
                () -> service.createAccount(card("hsbc", "4321", null, "54321")));
        assertThat(notSix.getMessage()).isEqualTo("Last 6 digits must be 6 digits");
        assertThrows(ValidationException.class, () -> service.createAccount(card("hsbc", "4321", null, "65432a")));
        ValidationException wrongEnd = assertThrows(ValidationException.class,
                () -> service.createAccount(card("hsbc", "4321", null, "654320")));
        assertThat(wrongEnd.getMessage()).isEqualTo("Last 6 digits must end with the card's last 4 digits");
        verify(accountRepository, never()).save(any());
    }

    @Test
    void nonHsbcCardStoresLast6WithoutAPassword() {
        Account saved = service.createAccount(card("icici", "4321", null, "654321"));
        assertThat(saved.getCreditCardDetails().getLast6()).isEqualTo("654321");
        assertThat(pwd(saved)).isNull();
        verify(publisher, never()).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void updateAddingLast6ToABlankPasswordDerives() {
        Account a = stored("hsbc", "4321", null, null);
        service.updateAccount(a.getId(), card("hsbc", "4321", null, "654321"));
        assertThat(pwd(a)).isEqualTo(DERIVED);
        verify(publisher).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void updateKeepsAHandTypedPassword() {
        Account a = stored("hsbc", "4321", "typed-secret", null);
        service.updateAccount(a.getId(), card("hsbc", "4321", null, "654321"));
        assertThat(pwd(a)).isEqualTo("typed-secret");
        assertThat(a.getCreditCardDetails().getLast6()).isEqualTo("654321");
        verify(publisher, never()).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void updateChangingLast6ReDerivesAPreviouslyDerivedPassword() {
        Account a = stored("hsbc", "4321", null, "654321");
        assertThat(pwd(a)).isEqualTo(DERIVED);
        service.updateAccount(a.getId(), card("hsbc", "3456", null, "123456"));
        assertThat(pwd(a)).isEqualTo("150190123456");
        verify(publisher).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void updateOmittingLast6KeepsIt() {
        Account a = stored("hsbc", "4321", null, "654321");
        service.updateAccount(a.getId(), card("hsbc", "4321", null, null));
        assertThat(a.getCreditCardDetails().getLast6()).isEqualTo("654321");
        assertThat(pwd(a)).isEqualTo(DERIVED);
        verify(publisher, never()).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void responseCarriesLast6() {
        Account saved = service.createAccount(card("hsbc", "4321", null, "654321"));
        assertThat(((AccountResponse.CreditCardAccountResponse) AccountResponse.from(saved)).last6()).isEqualTo("654321");
    }
}
