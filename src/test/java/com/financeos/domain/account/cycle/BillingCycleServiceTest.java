package com.financeos.domain.account.cycle;

import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.cycle.BillingCycleService.AccountCycle;
import com.financeos.domain.account.cycle.BillingCycles.Source;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.domain.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillingCycleServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock StatementRepository statementRepository;
    @Mock TransactionRepository transactionRepository;

    BillingCycleService service;
    final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BillingCycleService(accountRepository, statementRepository, transactionRepository);
        // effectiveDateSpan is a default method of the repository: run its real logic over the stubbed min/max
        when(transactionRepository.effectiveDateSpan(any(), any())).thenCallRealMethod();
    }

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private Account account(AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setType(type);
        return a;
    }

    private Statement stmt(LocalDate start, LocalDate end) {
        Statement s = new Statement();
        s.setPeriodStart(start);
        s.setPeriodEnd(end);
        s.setVerdict(StatementVerdict.AUTO_INGEST);
        return s;
    }

    @Test
    void accountCyclesCoversEveryAccountOfTheUser() {
        Account card = account(AccountType.credit_card);
        Account bank = account(AccountType.bank_account);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(bank, card));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenReturn(List.of());

        Map<UUID, BillingCycles> out = service.accountCycles(userId);

        assertEquals(2, out.size());
        assertTrue(out.containsKey(card.getId()));
        assertTrue(out.containsKey(bank.getId()));
        verify(statementRepository, never()).findByAccountIdOrderByPeriodEndDescNullsLast(bank.getId());
    }

    @Test
    void accountCyclesEmptyWhenUserHasNoAccounts() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());
        assertTrue(service.accountCycles(userId).isEmpty());
    }

    @Test
    void cyclesForCreditCardBuildsFromItsStatements() {
        Account card = account(AccountType.credit_card);
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId()))
                .thenReturn(List.of(stmt(d(2026, 1, 5), d(2026, 2, 4))));
        assertTrue(service.cyclesFor(card).hasStatements());
    }

    @Test
    void windowsReturnsCurrentAndPreviousCyclePerCard() {
        Account card = account(AccountType.credit_card);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId()))
                .thenReturn(List.of(stmt(d(2026, 1, 5), d(2026, 2, 4))));

        CycleWindows current = service.windows(userId, 0, d(2026, 2, 20));
        assertTrue(current.contains(card.getId(), d(2026, 2, 5)));
        assertTrue(current.contains(card.getId(), d(2026, 3, 4)));
        assertFalse(current.contains(card.getId(), d(2026, 2, 4)));

        CycleWindows previous = service.windows(userId, 1, d(2026, 2, 20));
        assertTrue(previous.contains(card.getId(), d(2026, 1, 5)));
        assertTrue(previous.contains(card.getId(), d(2026, 2, 4)));
    }

    @Test
    void windowsEmptyForUserWithoutCards() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of());
        CycleWindows w = service.windows(userId, 0, d(2026, 2, 20));
        assertNull(w.earliestStart());
    }

    @Test
    void windowsForCardWithoutStatementsUseCalendarMonth() {
        Account card = account(AccountType.credit_card);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenReturn(List.of());
        CycleWindows w = service.windows(userId, 0, d(2026, 2, 20));
        assertEquals(d(2026, 2, 1), w.earliestStart());
        assertEquals(d(2026, 2, 28), w.latestEnd());
    }

    @Test
    void cycleTableSpansMinEffectiveDateThroughToday() {
        Account card = account(AccountType.credit_card);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId()))
                .thenReturn(List.of(stmt(d(2026, 1, 5), d(2026, 2, 4))));
        when(transactionRepository.findMinEffectiveDateByAccountId(card.getId())).thenReturn(d(2026, 1, 10));
        when(transactionRepository.findMaxEffectiveDateByAccountId(card.getId())).thenReturn(d(2026, 2, 1));

        List<AccountCycle> table = service.cycleTable(userId, d(2026, 3, 10));

        assertEquals(3, table.size());
        assertEquals(d(2026, 1, 5), table.get(0).start());
        assertEquals(d(2026, 3, 5), table.get(2).start());
        assertEquals(d(2026, 4, 4), table.get(2).end());
        assertTrue(table.stream().allMatch(c -> c.accountId().equals(card.getId())));
    }

    @Test
    void cycleTableExtendsToFutureDatedTransactions() {
        Account card = account(AccountType.credit_card);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId()))
                .thenReturn(List.of(stmt(d(2026, 1, 5), d(2026, 2, 4))));
        when(transactionRepository.findMinEffectiveDateByAccountId(card.getId())).thenReturn(d(2026, 1, 10));
        when(transactionRepository.findMaxEffectiveDateByAccountId(card.getId())).thenReturn(d(2026, 5, 20));

        List<AccountCycle> table = service.cycleTable(userId, d(2026, 2, 10));

        // Jan 5-Feb 4, Feb 5-Mar 4, Mar 5-Apr 4, Apr 5-May 4, May 5-Jun 4
        assertEquals(5, table.size());
        assertEquals(d(2026, 5, 5), table.get(4).start());
    }

    @Test
    void cycleTableWhenAllTransactionsAreAfterTodayStillCoversThem() {
        Account card = account(AccountType.credit_card);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenReturn(List.of());
        when(transactionRepository.findMinEffectiveDateByAccountId(card.getId())).thenReturn(d(2026, 6, 10));
        when(transactionRepository.findMaxEffectiveDateByAccountId(card.getId())).thenReturn(d(2026, 6, 12));

        List<AccountCycle> table = service.cycleTable(userId, d(2026, 3, 1));

        assertEquals(1, table.size());
        assertEquals(d(2026, 6, 1), table.get(0).start());
    }

    @Test
    void cycleTableSkipsCardsWithoutTransactions() {
        Account card = account(AccountType.credit_card);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenReturn(List.of());
        when(transactionRepository.findMinEffectiveDateByAccountId(card.getId())).thenReturn(null);

        assertTrue(service.cycleTable(userId, d(2026, 3, 1)).isEmpty());
        verify(transactionRepository, never()).findMaxEffectiveDateByAccountId(any());
    }

    @Test
    void cycleTableUsesTodayWhenMaxIsNull() {
        Account card = account(AccountType.credit_card);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenReturn(List.of());
        when(transactionRepository.findMinEffectiveDateByAccountId(card.getId())).thenReturn(d(2026, 2, 3));
        when(transactionRepository.findMaxEffectiveDateByAccountId(card.getId())).thenReturn(null);

        List<AccountCycle> table = service.cycleTable(userId, d(2026, 3, 10));
        assertEquals(2, table.size());
        assertEquals(Source.CALENDAR_MONTH, BillingCycles.fromStatements(List.of()).containing(d(2026, 2, 3)).source());
    }

    // ---- round 2: every account type, account-scoped windows ----

    private Account named(AccountType type, String name) {
        Account a = account(type);
        a.setName(name);
        return a;
    }

    @Test
    void cyclesForNonCreditCardAccountsAreCalendarMonthsWithoutReadingStatements() {
        for (AccountType type : List.of(AccountType.bank_account, AccountType.broker, AccountType.generic)) {
            Account other = account(type);
            BillingCycles c = service.cyclesFor(other);
            assertFalse(c.hasStatements(), type.name());
            assertEquals(Source.CALENDAR_MONTH, c.containing(d(2026, 2, 14)).source());
        }
        verifyNoInteractions(statementRepository);
    }

    @Test
    void cycleTableIncludesNonCardAccountsAsCalendarMonths() {
        Account bank = account(AccountType.bank_account);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(bank));
        when(transactionRepository.findMinEffectiveDateByAccountId(bank.getId())).thenReturn(d(2026, 1, 20));
        when(transactionRepository.findMaxEffectiveDateByAccountId(bank.getId())).thenReturn(null);

        List<AccountCycle> table = service.cycleTable(userId, d(2026, 3, 10));

        assertEquals(3, table.size());
        assertEquals(d(2026, 1, 1), table.get(0).start());
        assertEquals(d(2026, 1, 31), table.get(0).end());
        assertEquals(d(2026, 3, 31), table.get(2).end());
        assertTrue(table.stream().allMatch(c -> c.accountId().equals(bank.getId())));
    }

    @Test
    void windowsCoverNonCardAccountsToo() {
        Account bank = account(AccountType.bank_account);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(bank));

        CycleWindows w = service.windows(userId, 1, d(2026, 3, 10));

        assertTrue(w.contains(bank.getId(), d(2026, 2, 15)));
        assertFalse(w.contains(bank.getId(), d(2026, 3, 1)));
    }

    private void twoAccounts(Account card, Account bank) {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(card, bank));
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId()))
                .thenReturn(List.of(stmt(d(2026, 1, 5), d(2026, 2, 4))));
    }

    @Test
    void windowsWithAnAccountRefAreLimitedToThatAccountById() {
        Account card = named(AccountType.credit_card, "HDFC Regalia");
        Account bank = named(AccountType.bank_account, "Savings");
        twoAccounts(card, bank);

        CycleWindows w = service.windows(userId, 0, d(2026, 2, 20), card.getId().toString());

        assertEquals(java.util.Set.of(card.getId()), w.byAccount().keySet());
        assertEquals(d(2026, 2, 5), w.earliestStart());
    }

    @Test
    void windowsWithAnAccountRefMatchTheNameIgnoringCase() {
        Account card = named(AccountType.credit_card, "HDFC Regalia");
        Account bank = named(AccountType.bank_account, "Savings");
        twoAccounts(card, bank);

        CycleWindows w = service.windows(userId, 0, d(2026, 2, 20), "savings");

        assertEquals(java.util.Set.of(bank.getId()), w.byAccount().keySet());
        assertEquals(d(2026, 2, 1), w.earliestStart());
        assertEquals(d(2026, 2, 28), w.latestEnd());
    }

    @Test
    void windowsWithAnUnknownAccountRefAreEmpty() {
        Account card = named(AccountType.credit_card, "HDFC Regalia");
        Account bank = named(AccountType.bank_account, "Savings");
        twoAccounts(card, bank);

        CycleWindows w = service.windows(userId, 0, d(2026, 2, 20), "no such account");

        assertTrue(w.byAccount().isEmpty());
        assertNull(w.earliestStart());
    }

    @Test
    void windowsWithANullAccountRefCoverAllAccounts() {
        Account card = named(AccountType.credit_card, "HDFC Regalia");
        Account bank = named(AccountType.bank_account, "Savings");
        twoAccounts(card, bank);

        assertEquals(2, service.windows(userId, 0, d(2026, 2, 20), null).byAccount().size());
        assertEquals(2, service.windows(userId, 0, d(2026, 2, 20)).byAccount().size());
    }

    @Test
    void anAccountWithoutANameDoesNotMatchANameRef() {
        Account unnamed = account(AccountType.bank_account);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(unnamed));

        assertTrue(service.windows(userId, 0, d(2026, 2, 20), "anything").byAccount().isEmpty());
    }
}
