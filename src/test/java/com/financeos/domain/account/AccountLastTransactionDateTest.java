package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.account.dto.AccountResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.holding.HoldingValuationService;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.gmail.domain.GmailBackfillDemandRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** lastTransactionDate is filled only by populateLastTransactionDates (the GET list/single path); every other read skips the query. */
class AccountLastTransactionDateTest {

    private AccountRepository accountRepository;
    private TransactionRepository transactionRepository;
    private AccountService service;
    private HoldingValuationService holdingValuationService;
    private User user;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        holdingValuationService = mock(HoldingValuationService.class);
        when(holdingValuationService.getBrokerMarketValue(org.mockito.ArgumentMatchers.any())).thenReturn(BigDecimal.ZERO);
        service = new AccountService(accountRepository, mock(CardholderRepository.class), mock(CardRepository.class),
                mock(UserRepository.class), mock(StatementRepository.class), transactionRepository,
                holdingValuationService, mock(GmailBackfillDemandRepository.class),
                mock(ApplicationEventPublisher.class));
        user = new User();
        user.setId(UUID.randomUUID());
        UserContext.setCurrentUserId(user.getId());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Account account(AccountType type) {
        Account a = new Account(type.name(), type);
        a.setId(UUID.randomUUID());
        a.setUser(user);
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
    void listFillsEachAccountFromOneBatchQueryAndNullWhenItHasNoRow() {
        Account withTxn = account(AccountType.bank_account);
        Account without = account(AccountType.credit_card);
        Account broker = account(AccountType.broker);
        when(transactionRepository.findLastTransactionDatesByAccountIds(anyCollection()))
                .thenReturn(List.of(row(withTxn.getId(), LocalDate.of(2026, 10, 5))));

        service.populateLastTransactionDates(List.of(withTxn, without, broker));

        assertEquals(LocalDate.of(2026, 10, 5), withTxn.getLastTransactionDate());
        assertNull(without.getLastTransactionDate());
        assertNull(broker.getLastTransactionDate(), "a broker's balance is not made of transactions");
        verify(transactionRepository, times(1)).findLastTransactionDatesByAccountIds(anyCollection());
        assertEquals(LocalDate.of(2026, 10, 5),
                ((AccountResponse.BankAccountResponse) AccountResponse.from(withTxn)).lastTransactionDate());
        assertNull(((AccountResponse.CreditCardAccountResponse) AccountResponse.from(without)).lastTransactionDate());
    }

    @Test
    void emptyListRunsNoQuery() {
        service.populateLastTransactionDates(List.of());

        verify(transactionRepository, never()).findLastTransactionDatesByAccountIds(anyCollection());
    }

    @Test
    @SuppressWarnings("unchecked")
    void idsAreChunkedAt900() {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 1801; i++) {
            accounts.add(account(AccountType.bank_account));
        }
        when(transactionRepository.findLastTransactionDatesByAccountIds(anyCollection())).thenAnswer(inv -> {
            List<TransactionRepository.AccountLastDateProjection> rows = new ArrayList<>();
            for (UUID id : (Collection<UUID>) inv.getArgument(0)) {
                rows.add(row(id, LocalDate.of(2026, 1, 1)));
            }
            return rows;
        });

        service.populateLastTransactionDates(accounts);

        ArgumentCaptor<Collection<UUID>> chunks = ArgumentCaptor.forClass(Collection.class);
        verify(transactionRepository, times(3)).findLastTransactionDatesByAccountIds(chunks.capture());
        assertEquals(List.of(900, 900, 1), chunks.getAllValues().stream().map(Collection::size).toList());
        assertEquals(LocalDate.of(2026, 1, 1), accounts.get(1800).getLastTransactionDate());
    }

    @Test
    void singleAccountReadsItsDateThroughTheSameBatchQuery() {
        Account bank = account(AccountType.bank_account);
        when(transactionRepository.findLastTransactionDatesByAccountIds(anyCollection()))
                .thenReturn(List.of(row(bank.getId(), LocalDate.of(2026, 10, 9))));

        service.populateLastTransactionDates(List.of(bank));

        assertEquals(LocalDate.of(2026, 10, 9), bank.getLastTransactionDate());
    }

    @Test
    void singleAccountWithNoTransactionsIsNull() {
        Account bank = account(AccountType.bank_account);

        service.populateLastTransactionDates(List.of(bank));

        assertNull(bank.getLastTransactionDate());
    }

    @Test
    void otherReadersRunNoLastDateQuery() {
        Account bank = account(AccountType.bank_account);
        Account card = account(AccountType.credit_card);
        when(accountRepository.findAll()).thenReturn(List.of(bank, card));
        when(accountRepository.findById(bank.getId())).thenReturn(Optional.of(bank));

        service.getAllAccounts();
        service.getAccountById(bank.getId());
        service.findOwnedAccount(bank.getId());
        service.populateLiveBalance(bank);
        service.populateBalances(List.of(bank, card));

        verify(transactionRepository, never()).findLastTransactionDatesByAccountIds(anyCollection());
        assertNull(bank.getLastTransactionDate());
        assertNull(card.getLastTransactionDate());
    }
}
