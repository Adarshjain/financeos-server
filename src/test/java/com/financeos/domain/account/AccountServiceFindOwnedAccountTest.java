package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** {@link AccountService#findOwnedAccount}: the user's own account with its balance, else empty. */
class AccountServiceFindOwnedAccountTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private HoldingValuationService holdingValuationService;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        holdingValuationService = mock(HoldingValuationService.class);
        accountService = new AccountService(accountRepository, mock(CardholderRepository.class), mock(CardRepository.class),
                mock(UserRepository.class), statementRepository, transactionRepository, holdingValuationService,
                mock(GmailBackfillDemandRepository.class), mock(ApplicationEventPublisher.class));
        UserContext.setCurrentUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void ownAnchoredAccountComesWithItsAnchoredBalance() {
        Account account = account(USER_ID, AccountType.bank_account);
        LocalDate periodEnd = LocalDate.of(2026, 9, 30);
        StatementRepository.AnchorStatementProjection anchor = mock(StatementRepository.AnchorStatementProjection.class);
        when(anchor.getPeriodEnd()).thenReturn(periodEnd);
        when(anchor.getClosingBalance()).thenReturn(new BigDecimal("1000"));
        when(statementRepository.findEligibleAnchorStatements(any(), any())).thenReturn(List.of(anchor));
        TransactionRepository.BalanceAggregatesProjection sums = mock(TransactionRepository.BalanceAggregatesProjection.class);
        when(sums.getTotalSum()).thenReturn(new BigDecimal("900"));
        when(sums.getPostAnchorSum()).thenReturn(new BigDecimal("-200"));
        when(transactionRepository.findBalanceAggregatesByAccountId(account.getId(), periodEnd)).thenReturn(sums);

        Account found = accountService.findOwnedAccount(account.getId()).orElseThrow();

        assertSame(account, found);
        assertEquals(new BigDecimal("800"), found.getCalculatedBalance());
        assertTrue(found.getBalanceAnchored());
        assertEquals(periodEnd, found.getAnchorDate());
    }

    @Test
    void ownUnanchoredAccountSumsAllTransactions() {
        Account account = account(USER_ID, AccountType.generic);
        when(statementRepository.findEligibleAnchorStatements(any(), any())).thenReturn(List.of());
        when(transactionRepository.findTotalTransactionSumByAccountId(account.getId())).thenReturn(new BigDecimal("-75"));

        Account found = accountService.findOwnedAccount(account.getId()).orElseThrow();

        assertEquals(new BigDecimal("-75"), found.getCalculatedBalance());
        assertFalse(found.getBalanceAnchored());
        assertNull(found.getAnchorDate());
    }

    @Test
    void ownBrokerAccountIsMarketValuePlusCash() {
        Account account = account(USER_ID, AccountType.broker);
        account.setBrokerDetails(new AccountBrokerDetails(account, "zerodha", "AB1", new BigDecimal("250")));
        when(holdingValuationService.getBrokerMarketValue(account.getId())).thenReturn(new BigDecimal("1000"));

        Account found = accountService.findOwnedAccount(account.getId()).orElseThrow();

        assertEquals(new BigDecimal("1250"), found.getCalculatedBalance());
    }

    @Test
    void someoneElsesAccountIsEmptyAndNeverValued() {
        Account account = account(UUID.randomUUID(), AccountType.bank_account);

        assertTrue(accountService.findOwnedAccount(account.getId()).isEmpty());
        verifyNoInteractions(statementRepository, transactionRepository, holdingValuationService);
    }

    @Test
    void accountWithoutAnOwnerIsEmpty() {
        Account account = account(null, AccountType.bank_account);
        account.setUser(null);

        assertTrue(accountService.findOwnedAccount(account.getId()).isEmpty());
    }

    @Test
    void missingAccountIsEmpty() {
        UUID id = UUID.randomUUID();
        when(accountRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(accountService.findOwnedAccount(id).isEmpty());
    }

    private Account account(UUID ownerId, AccountType type) {
        User owner = new User();
        owner.setId(ownerId);
        Account account = new Account("Acc", type);
        account.setId(UUID.randomUUID());
        account.setUser(owner);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return account;
    }
}
