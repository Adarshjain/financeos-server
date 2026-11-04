package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** suggestClose on the account list and single reads, and the "Not yet" dismissal. */
class AccountSuggestCloseTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final AccountService service = new AccountService(accountRepository, mock(CardholderRepository.class), mock(CardRepository.class),
            mock(UserRepository.class), mock(StatementRepository.class), transactionRepository,
            mock(HoldingValuationService.class), mock(GmailBackfillDemandRepository.class), mock(ApplicationEventPublisher.class));
    private User user;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private Account account(String name, String balance, int createdDaysAgo) {
        Account a = new Account(name, AccountType.bank_account);
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setCalculatedBalance(new BigDecimal(balance));
        a.setCreatedAt(TODAY.minusDays(createdDaysAgo).atTime(12, 0).atZone(IST).toInstant());
        return a;
    }

    private static TransactionRepository.AccountLastDateProjection row(UUID id, LocalDate date) {
        return new TransactionRepository.AccountLastDateProjection() {
            public UUID getAccountId() {
                return id;
            }

            public LocalDate getLastDate() {
                return date;
            }
        };
    }

    @Test
    void theListFlagsEachAccountFromOneBatchQuery() {
        Account dormant = account("Dormant", "0", 400);
        Account busy = account("Busy", "0", 400);
        Account funded = account("Funded", "500", 400);
        Account young = account("Young", "0", 10);
        Account closed = account("Closed", "0", 400);
        closed.setClosedOn(TODAY.minusDays(5));
        when(transactionRepository.findLastTransactionDatesByAccountIds(anyCollection())).thenReturn(List.of(
                row(dormant.getId(), TODAY.minusDays(200)), row(busy.getId(), TODAY.minusDays(3)),
                row(funded.getId(), TODAY.minusDays(200))));

        service.populateLastTransactionDates(List.of(dormant, busy, funded, young, closed));

        assertTrue(dormant.isSuggestClose());
        assertFalse(busy.isSuggestClose());
        assertFalse(funded.isSuggestClose());
        assertFalse(young.isSuggestClose());
        assertFalse(closed.isSuggestClose());
        verify(transactionRepository, times(1)).findLastTransactionDatesByAccountIds(anyCollection());
    }

    @Test
    void theResponseCarriesTheFlagForTheListAndSingleShapes() {
        Account dormant = account("Dormant", "0", 400);
        Account busy = account("Busy", "0", 400);
        when(transactionRepository.findLastTransactionDatesByAccountIds(anyCollection())).thenReturn(List.of(row(busy.getId(), TODAY.minusDays(3))));

        service.populateLastTransactionDates(List.of(dormant, busy));

        assertTrue(AccountResponse.from(dormant).suggestClose());
        assertFalse(AccountResponse.from(busy).suggestClose());
        Account card = account("Card", "0", 400);
        card.setType(AccountType.credit_card);
        service.populateLastTransactionDates(List.of(card));
        assertTrue(AccountResponse.from(card).suggestClose());
    }

    @Test
    void aDismissedAccountIsNotSuggestedAgainForNinetyDays() {
        Account a = account("Dormant", "0", 400);
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findLastTransactionDatesByAccountIds(anyCollection())).thenReturn(List.of());

        service.dismissClosePrompt(a.getId());

        assertNotNull(a.getClosePromptDismissedAt());
        service.populateLastTransactionDates(List.of(a));
        assertFalse(a.isSuggestClose());
        a.setClosePromptDismissedAt(TODAY.minusDays(91).atTime(12, 0).atZone(IST).toInstant());
        service.populateLastTransactionDates(List.of(a));
        assertTrue(a.isSuggestClose());
    }

    @Test
    void anotherUsersAccountCannotBeDismissed() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        Account foreign = account("Theirs", "0", 400);
        foreign.setUser(stranger);
        when(accountRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThrows(ValidationException.class, () -> service.dismissClosePrompt(foreign.getId()));

        assertNull(foreign.getClosePromptDismissedAt());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void anUnknownAccountIsNotFound() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.dismissClosePrompt(id));
    }
}
