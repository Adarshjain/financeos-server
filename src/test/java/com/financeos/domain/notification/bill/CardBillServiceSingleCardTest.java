package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountCreditCardDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.CreditLimitPool;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** GET /bills?accountId=X: one card's bill equals its entry in the full list, and no other card's rows are read. */
class CardBillServiceSingleCardTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private AccountService accountService;
    private CardBillService service;
    private User user;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        accountService = mock(AccountService.class);
        when(accountService.populateLiveBalance(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CardBillService(accountRepository, statementRepository, transactionRepository, accountService);
        BatchRepositoryBridge.install(statementRepository, transactionRepository, accountService);
        user = new User();
        user.setId(UUID.randomUUID());
        when(transactionRepository.sumCreditsAfter(any(), any())).thenReturn(BigDecimal.ZERO);
        when(transactionRepository.sumDebitsAfter(any(), any())).thenReturn(new BigDecimal("500"));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Account card(String name, CreditLimitPool pool) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setName(name);
        a.setType(AccountType.credit_card);
        AccountCreditCardDetails details = new AccountCreditCardDetails(a, new BigDecimal("200000"), null);
        details.setLimitPool(pool);
        a.setCreditCardDetails(details);
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    private void withStatement(Account account, int periodEndDaysAgo, String total) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setAccount(account);
        s.setStatementType("credit_card");
        LocalDate end = TODAY.minusDays(periodEndDaysAgo);
        s.setPeriodStart(end.minusDays(29));
        s.setPeriodEnd(end);
        s.setCreatedAt(end.plusDays(1).atStartOfDay(IST).toInstant());
        s.setTransactionCount(5);
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(user);
        d.setTotalAmountDue(new BigDecimal(total));
        d.setPaymentDueDate(end.plusDays(20));
        s.setCreditCardDetails(d);
        when(statementRepository.findQualifyingCreditCardStatements(account.getId())).thenReturn(List.of(s));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(account.getId())).thenReturn(List.of(s));
    }

    private CardBill entryFor(List<CardBill> all, Account a) {
        return all.stream().filter(b -> b.accountId().equals(a.getId())).findFirst().orElseThrow();
    }

    @Test
    void unpooledCardMatchesItsEntryInTheFullListAndReadsNoOtherCard() {
        Account target = card("Axis", null);
        Account other = card("ICICI", null);
        withStatement(target, 10, "1000");
        withStatement(other, 12, "2000");
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card)).thenReturn(List.of(target, other));
        CardBill expected = entryFor(service.listBills(user.getId()), target);

        org.mockito.Mockito.clearInvocations(statementRepository, transactionRepository, accountService);
        List<CardBill> one = service.listBills(user.getId(), target.getId());

        assertEquals(List.of(expected), one);
        verify(statementRepository).findQualifyingCreditCardStatementKeysIn(List.of(target.getId()));
        verify(statementRepository).findPeriodsByAccountIdIn(List.of(target.getId()));
        verify(statementRepository, never()).findQualifyingCreditCardStatements(other.getId());
        verify(statementRepository, never()).findByAccountIdOrderByPeriodEndDescNullsLast(other.getId());
        verify(transactionRepository, never()).sumDebitsAfter(org.mockito.ArgumentMatchers.eq(other.getId()), any());
        verify(accountRepository, never()).findLimitPoolMembers(any(), any());
        ArgumentCaptor<List<Account>> populated = ArgumentCaptor.forClass(List.class);
        verify(accountService).populateBalances(populated.capture());
        assertEquals(List.of(target), populated.getValue());
    }

    @Test
    void pooledCardMatchesItsEntryAndPopulatesEveryPoolMemberButReadsOnlyItsOwnStatements() {
        CreditLimitPool pool = new CreditLimitPool(user, "Shared", new BigDecimal("500000"));
        pool.setId(UUID.randomUUID());
        Account target = card("Axis", pool);
        Account sibling = card("Axis Neo", pool);
        Account closedMember = card("Axis Old", pool);
        closedMember.setClosedOn(TODAY.minusDays(3));
        Account unrelated = card("ICICI", null);
        for (Account a : List.of(target, sibling, closedMember, unrelated)) {
            withStatement(a, 10, "1000");
        }
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card))
                .thenReturn(List.of(target, sibling, closedMember, unrelated));
        when(accountRepository.findLimitPoolMembers(pool.getId(), user.getId()))
                .thenReturn(List.of(target, sibling, closedMember));
        CardBill expected = entryFor(service.listBills(user.getId()), target);

        org.mockito.Mockito.clearInvocations(statementRepository, transactionRepository, accountService);
        List<CardBill> one = service.listBills(user.getId(), target.getId());

        assertEquals(List.of(expected), one);
        ArgumentCaptor<List<Account>> populated = ArgumentCaptor.forClass(List.class);
        verify(accountService).populateBalances(populated.capture());
        assertEquals(3, populated.getValue().size());
        assertTrue(populated.getValue().containsAll(List.of(target, sibling, closedMember)));
        verify(statementRepository).findQualifyingCreditCardStatementKeysIn(List.of(target.getId()));
        verify(statementRepository, never()).findQualifyingCreditCardStatements(sibling.getId());
        verify(statementRepository, never()).findByAccountIdOrderByPeriodEndDescNullsLast(closedMember.getId());
        verify(transactionRepository, never()).sumDebitsAfter(org.mockito.ArgumentMatchers.eq(sibling.getId()), any());
    }

    @Test
    void anotherUsersMissingClosedOrNonCardAccountYieldsEmpty() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        Account theirs = card("Theirs", null);
        theirs.setUser(stranger);
        Account closed = card("Closed", null);
        closed.setClosedOn(TODAY.minusDays(1));
        Account bank = card("Bank", null);
        bank.setType(AccountType.bank_account);

        assertTrue(service.listBills(user.getId(), theirs.getId()).isEmpty());
        assertTrue(service.listBills(user.getId(), closed.getId()).isEmpty());
        assertTrue(service.listBills(user.getId(), bank.getId()).isEmpty());
        assertTrue(service.listBills(user.getId(), UUID.randomUUID()).isEmpty());
        verify(statementRepository, never()).findQualifyingCreditCardStatementKeysIn(any());
        verify(accountService, never()).populateBalances(any());
    }
}
