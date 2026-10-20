package com.financeos.domain.notification.bill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountCreditCardDetails;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.CardUtilization;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GET /bills/history: one card's bills in one read, each equal to the per-statement bill. */
class CardBillHistoryTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private TransactionRepository transactionRepository;
    private AccountService accountService;
    private CardBillService service;
    private User user;
    private Account card;
    private final List<Statement> statements = new ArrayList<>();
    /** date -> signed amount (credit positive) of the card's transactions. */
    private final List<Object[]> txns = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        accountService = mock(AccountService.class);
        when(accountService.populateLiveBalance(any())).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setEffectiveCreditLimit(CardUtilization.creditLimit(a, statementRepository));
            return a;
        });
        service = new CardBillService(accountRepository, statementRepository, transactionRepository, accountService);
        user = new User();
        user.setId(UUID.randomUUID());
        card = newCard(user, AccountType.credit_card);

        // Five statements, newest first, plus one undated; period ends 10, 40, 70, 100, 130 days ago.
        for (int i = 0; i < 5; i++) {
            statements.add(statement(TODAY.minusDays(10 + 30L * i), "1000"));
        }
        Statement undated = statement(TODAY.minusDays(10), "500");
        undated.setPeriodStart(null);
        undated.setPeriodEnd(null);
        statements.add(undated);
        txns.add(new Object[] {TODAY.minusDays(35), new BigDecimal("-200")});
        txns.add(new Object[] {TODAY.minusDays(20), new BigDecimal("300")});
        txns.add(new Object[] {TODAY.minusDays(5), new BigDecimal("-50")});
        txns.add(new Object[] {TODAY.minusDays(3), new BigDecimal("1000")});

        when(statementRepository.findByAccountIdInOrderByPeriodEndDescNullsLast(any())).thenAnswer(inv -> List.copyOf(statements));
        when(statementRepository.findLastLineDates(any())).thenReturn(List.of());
        when(statementRepository.findByAccountIdOrderByPeriodEndDescNullsLast(card.getId())).thenAnswer(inv -> List.copyOf(statements));
        when(transactionRepository.sumCreditsAfter(any(), any())).thenAnswer(inv -> sum(inv.getArgument(1), true));
        when(transactionRepository.sumDebitsAfter(any(), any())).thenAnswer(inv -> sum(inv.getArgument(1), false));
        when(transactionRepository.sumsByDayAfter(any(), any())).thenAnswer(inv -> {
            LocalDate after = inv.getArgument(1);
            List<TransactionRepositoryCustom.DaySums> out = new ArrayList<>();
            for (Object[] t : txns) {
                LocalDate d = (LocalDate) t[0];
                BigDecimal amount = (BigDecimal) t[1];
                if (d.isAfter(after)) {
                    out.add(new TransactionRepositoryCustom.DaySums(d,
                            amount.signum() > 0 ? amount : BigDecimal.ZERO, amount.signum() < 0 ? amount.abs() : BigDecimal.ZERO));
                }
            }
            return out;
        });
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private BigDecimal sum(LocalDate after, boolean credits) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] t : txns) {
            BigDecimal amount = (BigDecimal) t[1];
            if (((LocalDate) t[0]).isAfter(after) && (amount.signum() > 0) == credits) {
                total = total.add(amount.abs());
            }
        }
        return total;
    }

    private Account newCard(User owner, AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setUser(owner);
        a.setName("Axis");
        a.setType(type);
        a.setCreditCardDetails(new AccountCreditCardDetails(a, new BigDecimal("200000"), null));
        when(accountRepository.findById(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    private Statement statement(LocalDate end, String total) {
        Statement s = new Statement();
        s.setId(UUID.randomUUID());
        s.setUser(user);
        s.setAccount(card);
        s.setStatementType("credit_card");
        s.setPeriodStart(end.minusDays(29));
        s.setPeriodEnd(end);
        s.setCreatedAt(end.plusDays(1).atStartOfDay(IST).toInstant());
        s.setTransactionCount(5);
        StatementCreditCardDetails d = new StatementCreditCardDetails(s);
        d.setUser(user);
        d.setTotalAmountDue(new BigDecimal(total));
        d.setPaymentDueDate(end.plusDays(20));
        s.setCreditCardDetails(d);
        return s;
    }

    private List<UUID> ids(List<CardBill> bills) {
        return bills.stream().map(CardBill::statementId).toList();
    }

    @Test
    void everyStatementNewestFirstByStatementDateEachEqualToItsPerStatementBill() {
        List<CardBill> all = service.historyForAccount(user.getId(), card.getId(), null, null, null);

        assertEquals(6, all.size());
        // The undated one is created TODAY-9, so it sorts between the TODAY-10 and TODAY-40 statements by that date.
        List<Statement> expected = new ArrayList<>(statements.subList(0, 5));
        expected.add(0, statements.get(5));
        assertEquals(expected.stream().map(Statement::getId).toList(), ids(all));
        for (int i = 0; i < all.size(); i++) {
            when(statementRepository.findById(expected.get(i).getId())).thenReturn(Optional.of(expected.get(i)));
            assertEquals(service.findByStatementId(user.getId(), expected.get(i).getId()), all.get(i), "bill " + i);
        }
    }

    @Test
    void limitKeepsTheNewestN() {
        List<CardBill> two = service.historyForAccount(user.getId(), card.getId(), 2, null, null);
        assertEquals(List.of(statements.get(5).getId(), statements.get(0).getId()), ids(two));
    }

    @Test
    void rangeBoundsStatementDateInclusive() {
        LocalDate from = statements.get(3).getPeriodEnd();
        LocalDate to = statements.get(1).getPeriodEnd();
        List<CardBill> range = service.historyForAccount(user.getId(), card.getId(), null, from, to);
        assertEquals(List.of(statements.get(1).getId(), statements.get(2).getId(), statements.get(3).getId()), ids(range));
    }

    @Test
    void undatedStatementInsideRangeIsReturnedByItsCreatedDate() {
        Statement undated = statements.get(5);
        List<CardBill> range = service.historyForAccount(user.getId(), card.getId(), null,
                TODAY.minusDays(9), TODAY.minusDays(9));
        assertEquals(List.of(undated.getId()), ids(range));
    }

    @Test
    void undatedStatementOutsideRangeIsExcluded() {
        List<CardBill> range = service.historyForAccount(user.getId(), card.getId(), null,
                TODAY.minusDays(45), TODAY.minusDays(35));
        assertEquals(List.of(statements.get(1).getId()), ids(range));
    }

    @Test
    void undatedStatementCreatedLateIsDatedInTheAppZoneNotUtc() {
        Statement undated = statements.get(5);
        // 23:30 IST on TODAY-9 is still TODAY-9 in IST though it is the same UTC day; 00:30 IST next day is the previous UTC day.
        undated.setCreatedAt(TODAY.minusDays(8).atTime(0, 30).atZone(IST).toInstant());
        List<CardBill> range = service.historyForAccount(user.getId(), card.getId(), null,
                TODAY.minusDays(8), TODAY.minusDays(8));
        assertEquals(List.of(undated.getId()), ids(range));
    }

    @Test
    void limitAndRangeCombine() {
        LocalDate from = statements.get(3).getPeriodEnd();
        List<CardBill> both = service.historyForAccount(user.getId(), card.getId(), 2, from, null);
        assertEquals(List.of(statements.get(5).getId(), statements.get(0).getId()), ids(both));
    }

    @Test
    void emptyWhenNothingMatches() {
        assertTrue(service.historyForAccount(user.getId(), card.getId(), null,
                TODAY.plusDays(1), TODAY.plusDays(9)).isEmpty());
        statements.clear();
        assertTrue(service.historyForAccount(user.getId(), card.getId(), null, null, null).isEmpty());
    }

    @Test
    void readsLineDatesAndSumsOnceNotPerStatement() {
        service.historyForAccount(user.getId(), card.getId(), null, null, null);
        verify(statementRepository, times(1)).findByAccountIdInOrderByPeriodEndDescNullsLast(any());
        verify(statementRepository, times(1)).findLastLineDates(any());
        verify(transactionRepository, times(1)).sumsByDayAfter(any(), any());
        verify(statementRepository, never()).findLastLineDate(any());
        verify(transactionRepository, never()).sumCreditsAfter(any(), any());
        verify(transactionRepository, never()).sumDebitsAfter(any(), any());
        verify(accountService, times(1)).populateLiveBalance(any());
    }

    @Test
    void skipsStatementsWithoutCardDetails() {
        statements.get(0).setCreditCardDetails(null);
        List<CardBill> all = service.historyForAccount(user.getId(), card.getId(), null, null, null);
        assertEquals(5, all.size());
        assertTrue(!ids(all).contains(statements.get(0).getId()));
    }

    @Test
    void unknownForeignAndNonCardAccountsAreRefused() {
        assertThrows(ResourceNotFoundException.class,
                () -> service.historyForAccount(user.getId(), UUID.randomUUID(), null, null, null));
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        Account theirs = newCard(stranger, AccountType.credit_card);
        assertThrows(ValidationException.class, () -> service.historyForAccount(user.getId(), theirs.getId(), null, null, null));
        Account bank = newCard(user, AccountType.bank_account);
        assertThrows(ValidationException.class, () -> service.historyForAccount(user.getId(), bank.getId(), null, null, null));
        verify(statementRepository, never()).findByAccountIdInOrderByPeriodEndDescNullsLast(any());
    }

    @Test
    void badLimitOrRangeIsRefused() {
        assertThrows(ValidationException.class, () -> service.historyForAccount(user.getId(), card.getId(), 0, null, null));
        assertThrows(ValidationException.class,
                () -> service.historyForAccount(user.getId(), card.getId(), null, TODAY, TODAY.minusDays(1)));
    }
}
