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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

class AccountServiceBankTest {

    private AccountRepository accountRepository;
    private AccountService accountService;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        accountService = new AccountService(accountRepository,
                mock(com.financeos.domain.account.card.CardholderRepository.class),
                mock(com.financeos.domain.account.card.CardRepository.class), userRepository,
                mock(StatementRepository.class), mock(TransactionRepository.class),
                mock(HoldingValuationService.class),
                mock(com.financeos.gmail.domain.GmailBackfillDemandRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private CreateAccountRequest bank(String bank) {
        return new CreateAccountRequest.BankAccountRequest("Savings", AccountType.bank_account, false,
                FinancialPosition.asset, null, new BigDecimal("100"), "1234", null, null, bank);
    }

    private CreateAccountRequest card(String bank) {
        return new CreateAccountRequest.CreditCardRequest("Card", AccountType.credit_card, false,
                FinancialPosition.liability, null, "4321", new BigDecimal("50000"), LocalDate.of(2026, 1, 1),
                null, null, null, null, null, null, bank);
    }

    private Account stored(String bank) {
        Account a = new Account("Savings", AccountType.bank_account);
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setBank(bank);
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    void createWithBank_storesItAndResponseCarriesIt() {
        Account saved = accountService.createAccount(bank("hdfc"));
        assertThat(saved.getBank()).isEqualTo("hdfc");
        assertThat(((AccountResponse.BankAccountResponse) AccountResponse.from(saved)).bank()).isEqualTo("hdfc");
        assertThat(accountService.createAccount(card("icici")).getBank()).isEqualTo("icici");
    }

    @Test
    void createWithoutBank_isNullAndResponseCarriesNull() {
        Account saved = accountService.createAccount(bank(null));
        assertThat(saved.getBank()).isNull();
        assertThat(((AccountResponse.BankAccountResponse) AccountResponse.from(saved)).bank()).isNull();
    }

    @Test
    void unknownBankId_isRejected() {
        ValidationException e = assertThrows(ValidationException.class, () -> accountService.createAccount(bank("not_a_bank")));
        assertThat(e.getMessage()).contains("Unknown bank");
        assertThrows(ValidationException.class, () -> accountService.createAccount(card("HDFC")));
        verify(accountRepository, never()).save(any());
    }

    @Test
    void brokerAndGeneric_neverCarryABank() {
        // the request types expose no bank, so one sent in the body is ignored
        assertThat(new CreateAccountRequest.GenericAccountRequest("Cash", AccountType.generic, false, null, null).bank()).isNull();
        assertThat(new CreateAccountRequest.BrokerRequest("Zerodha", AccountType.broker, false, null, null,
                "zerodha", null, null).bank()).isNull();
        assertThat(accountService.createAccount(new CreateAccountRequest.GenericAccountRequest(
                "Cash", AccountType.generic, false, null, null)).getBank()).isNull();
    }

    @Test
    void updateWithBank_replacesTheStoredBank() {
        Account a = stored("hdfc");
        assertThat(accountService.updateAccount(a.getId(), bank("axis")).getBank()).isEqualTo("axis");
    }

    @Test
    void updateOmittingBank_keepsTheStoredBank() {
        Account a = stored("hdfc");
        assertThat(accountService.updateAccount(a.getId(), bank(null)).getBank()).isEqualTo("hdfc");
    }

    @Test
    void updateWithUnknownBank_isRejectedAndKeepsStored() {
        Account a = stored("hdfc");
        assertThrows(ValidationException.class, () -> accountService.updateAccount(a.getId(), bank("zzz")));
        assertThat(a.getBank()).isEqualTo("hdfc");
    }
}
