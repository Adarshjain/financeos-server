package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementCreditCardDetails;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionRepositoryCustom;
import com.financeos.domain.user.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** listBills reads many cards' data with a fixed number of repository calls, not a set per card. */
class CardBillBatchTest {

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
        service = new CardBillService(accountRepository, statementRepository, transactionRepository, accountService);
        user = new User();
        user.setId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private Account card(String name) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setUser(user);
        a.setName(name);
        a.setType(AccountType.credit_card);
        a.setEffectiveCreditLimit(new BigDecimal("100000"));
        a.setUtilizationPct(new BigDecimal("10.0"));
        return a;
    }

    private Statement statement(Account card, BigDecimal total) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setAccount(card);
        s.setStatementType("credit_card");
        s.setPeriodStart(TODAY.minusDays(40));
        s.setPeriodEnd(TODAY.minusDays(10));
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(user);
        d.setTotalAmountDue(total);
        d.setPaymentDueDate(TODAY.plusDays(5));
        s.setCreditCardDetails(d);
        return s;
    }

    @Test
    void severalCardsAreReadWithOneCallPerKindAndNoPerCardCall() {
        Account billed1 = card("Billed one");
        Account billed2 = card("Billed two");
        Account awaiting = card("Awaiting");
        Statement s1 = statement(billed1, new BigDecimal("1000"));
        Statement s2 = statement(billed2, new BigDecimal("2000"));
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card))
                .thenReturn(List.of(billed1, billed2, awaiting));
        when(statementRepository.findQualifyingCreditCardStatementKeysIn(any()))
                .thenReturn(BatchRepositoryBridge.keysOf(List.of(s1, s2)));
        when(statementRepository.findWithCreditCardDetailsByIdIn(any())).thenReturn(List.of(s1, s2));
        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(BatchRepositoryBridge.periodsOf(List.of(s1, s2)));
        when(statementRepository.findLastLineDates(any())).thenReturn(List.of());
        when(transactionRepository.sumsAfterCutoffs(any())).thenReturn(Map.of(
                billed1.getId(), new TransactionRepositoryCustom.SumsAfter(new BigDecimal("1000"), new BigDecimal("300")),
                billed2.getId(), new TransactionRepositoryCustom.SumsAfter(BigDecimal.ZERO, new BigDecimal("50"))));
        when(transactionRepository.findTotalTransactionSumsByAccountIds(any())).thenReturn(List.of());

        List<CardBill> bills = service.listBills(user.getId());

        assertEquals(3, bills.size());
        CardBill paid = bills.stream().filter(b -> b.accountId().equals(billed1.getId())).findFirst().orElseThrow();
        assertEquals(BillStatus.PAID, paid.status(), "credits after the cutoff pay the bill");
        assertEquals(new BigDecimal("300"), paid.unbilledAmount());
        CardBill open = bills.stream().filter(b -> b.accountId().equals(billed2.getId())).findFirst().orElseThrow();
        assertEquals(BillStatus.OPEN, open.status());
        assertEquals(new BigDecimal("50"), open.unbilledAmount());
        assertEquals(new BigDecimal("10.0"), open.digest().utilizationPct());
        assertEquals(new BigDecimal("100000"), open.digest().creditLimit());
        assertEquals(BillStatus.AWAITING_STATEMENT, bills.stream()
                .filter(b -> b.accountId().equals(awaiting.getId())).findFirst().orElseThrow().status());

        verify(accountService, times(1)).populateBalances(any());
        verify(statementRepository, times(1)).findQualifyingCreditCardStatementKeysIn(any());
        verify(statementRepository, times(1)).findWithCreditCardDetailsByIdIn(any());
        verify(statementRepository, times(1)).findPeriodsByAccountIdIn(any());
        verify(transactionRepository, times(1)).sumsAfterCutoffs(any());
        verify(statementRepository, never()).findQualifyingCreditCardStatements(any());
        verify(statementRepository, never()).findLastLineDate(any());
        verify(statementRepository, never()).findByAccountIdOrderByPeriodEndDescNullsLast(any());
        verify(transactionRepository, never()).sumCreditsAfter(any(), any());
        verify(transactionRepository, never()).sumDebitsAfter(any(), any());
        verify(transactionRepository, never()).findTotalTransactionSumByAccountId(any());
        verify(accountService, never()).populateLiveBalance(any());
    }

    @Test
    void onlyTheLatestLiveStatementOfEachCardIsLoadedAndTheNextExpectedDateReadsEveryPeriod() {
        Account c = card("Two statements");
        Statement older = statement(c, new BigDecimal("500"));
        older.setPeriodStart(TODAY.minusDays(70));
        older.setPeriodEnd(TODAY.minusDays(41));
        older.setCreatedAt(java.time.Instant.parse("2026-08-01T00:00:00Z"));
        Statement newer = statement(c, new BigDecimal("900"));
        newer.setCreatedAt(java.time.Instant.parse("2026-09-01T00:00:00Z"));
        Statement undated = statement(c, new BigDecimal("1"));
        undated.setPeriodStart(null);
        undated.setPeriodEnd(null);
        Statement rejected = statement(c, new BigDecimal("2"));
        rejected.setPeriodStart(TODAY.minusDays(10));
        rejected.setPeriodEnd(TODAY.plusDays(20));
        rejected.setVerdict(com.financeos.domain.statement.StatementVerdict.REJECTED);
        List<Statement> all = List.of(older, newer, undated, rejected);
        when(accountRepository.findByUserIdAndType(user.getId(), AccountType.credit_card)).thenReturn(List.of(c));
        when(statementRepository.findQualifyingCreditCardStatementKeysIn(any()))
                .thenReturn(BatchRepositoryBridge.keysOf(List.of(older, newer, undated)));
        when(statementRepository.findWithCreditCardDetailsByIdIn(any())).thenReturn(List.of(newer));
        when(statementRepository.findPeriodsByAccountIdIn(any())).thenReturn(BatchRepositoryBridge.periodsOf(all));
        when(statementRepository.findLastLineDates(any())).thenReturn(List.of());
        when(transactionRepository.sumsAfterCutoffs(any())).thenReturn(Map.of(c.getId(),
                new TransactionRepositoryCustom.SumsAfter(BigDecimal.ZERO, BigDecimal.ZERO)));
        when(transactionRepository.findTotalTransactionSumsByAccountIds(any())).thenReturn(List.of());

        List<CardBill> bills = service.listBills(user.getId());

        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> loaded = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(statementRepository).findWithCreditCardDetailsByIdIn(loaded.capture());
        assertEquals(List.of(newer.getId()), List.copyOf(loaded.getValue()), "the older and the undated statement are never hydrated");
        assertEquals(1, bills.size());
        assertEquals(new BigDecimal("900"), bills.get(0).totalAmountDue());
        // Next expected = the cycle after the latest valid period end (the rejected one is ignored).
        assertEquals(com.financeos.domain.account.cycle.BillingCycles.fromStatements(all).containing(TODAY.minusDays(9)).end(),
                bills.get(0).nextStatementExpectedOn());
    }
}
