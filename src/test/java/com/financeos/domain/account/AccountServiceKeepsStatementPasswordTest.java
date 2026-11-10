package com.financeos.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.financeos.api.account.dto.CreateAccountRequest;
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
import java.util.Optional;
import java.util.UUID;

/** An edit that leaves the statement password box empty keeps the stored password (bank accounts and cards). */
class AccountServiceKeepsStatementPasswordTest {

    private AccountRepository accountRepository;
    private AccountService service;
    private ApplicationEventPublisher publisher;

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
        User user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CreateAccountRequest bank(String password) {
        return new CreateAccountRequest.BankAccountRequest("Savings", AccountType.bank_account, false,
                FinancialPosition.asset, null, new BigDecimal("100"), "4321", password, null, null);
    }

    private CreateAccountRequest card(String password) {
        return new CreateAccountRequest.CreditCardRequest("Card", AccountType.credit_card, false,
                FinancialPosition.liability, null, "4321", new BigDecimal("50000"), null,
                password, null, null, null, null, null, null, null, null, null, null);
    }

    private Account stored(CreateAccountRequest request) {
        Account created = service.createAccount(request);
        created.setId(UUID.randomUUID());
        when(accountRepository.findById(created.getId())).thenReturn(Optional.of(created));
        clearInvocations(publisher);
        return created;
    }

    @Test
    void bankEditWithNoPasswordKeepsTheStoredOne() {
        Account a = stored(bank("typed-secret"));
        service.updateAccount(a.getId(), bank(null));
        assertThat(a.getBankDetails().getStatementPassword()).isEqualTo("typed-secret");
        verify(publisher, never()).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void bankEditWithABlankPasswordKeepsTheStoredOne() {
        Account a = stored(bank("typed-secret"));
        service.updateAccount(a.getId(), bank("  "));
        assertThat(a.getBankDetails().getStatementPassword()).isEqualTo("typed-secret");
    }

    @Test
    void bankEditWithANewPasswordReplacesItAndPublishes() {
        Account a = stored(bank("typed-secret"));
        service.updateAccount(a.getId(), bank("new-secret"));
        assertThat(a.getBankDetails().getStatementPassword()).isEqualTo("new-secret");
        verify(publisher).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void cardEditWithNoPasswordKeepsTheStoredOne() {
        Account a = stored(card("typed-secret"));
        service.updateAccount(a.getId(), card(null));
        assertThat(a.getCreditCardDetails().getStatementPassword()).isEqualTo("typed-secret");
        verify(publisher, never()).publishEvent(any(StatementPasswordSavedEvent.class));
    }

    @Test
    void cardEditWithANewPasswordReplacesItAndPublishes() {
        Account a = stored(card("typed-secret"));
        service.updateAccount(a.getId(), card("new-secret"));
        assertThat(a.getCreditCardDetails().getStatementPassword()).isEqualTo("new-secret");
        verify(publisher).publishEvent(any(StatementPasswordSavedEvent.class));
    }
}
