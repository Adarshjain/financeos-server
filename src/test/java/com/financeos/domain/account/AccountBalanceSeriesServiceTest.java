package com.financeos.domain.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.account.dto.BalancePointResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;
import com.financeos.domain.transaction.TransactionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The daily balance series walks back from the current balance by signed day totals. */
class AccountBalanceSeriesServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 9);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UUID accountId = UUID.randomUUID();
    private AccountService accounts;
    private TransactionRepository transactions;
    private AccountBalanceSeriesService service;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        accounts = mock(AccountService.class);
        transactions = mock(TransactionRepository.class);
        service = new AccountBalanceSeriesService(accounts, transactions);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static TransactionRepository.DailySumProjection day(LocalDate date, String total) {
        return new TransactionRepository.DailySumProjection() {
            @Override public LocalDate getDate() { return date; }
            @Override public BigDecimal getTotal() { return new BigDecimal(total); }
        };
    }

    private Account account(AccountType type, String balance) {
        Account a = new Account();
        a.setId(accountId);
        a.setType(type);
        a.setCalculatedBalance(balance == null ? null : d(balance));
        when(accounts.findOwnedAccount(accountId)).thenReturn(Optional.of(a));
        return a;
    }

    private static List<String> balances(List<BalancePointResponse> points) {
        return points.stream().map(p -> p.balance().stripTrailingZeros().toPlainString()).toList();
    }

    @Test
    void walkBackUndoesEachDaysTotalAndFutureDatedOnes() {
        LocalDate first = TODAY.minusDays(3);
        List<BalancePointResponse> points = AccountBalanceSeriesService.walkBack(d("1000"), Map.of(
                TODAY.plusDays(2), d("-50"),       // future-dated debit is in the current balance
                TODAY, d("100"),
                TODAY.minusDays(2), d("-300")), first, TODAY);
        assertEquals(List.of(first, first.plusDays(1), first.plusDays(2), TODAY),
                points.stream().map(BalancePointResponse::date).toList());
        // end of today = 1000 - (-50) = 1050; yesterday = 1050 - 100 = 950; D-2 = 950; D-3 = 950 - (-300) = 1250
        assertEquals(List.of("1250", "950", "950", "1050"), balances(points));
    }

    @Test
    void aMissingBalanceStartsFromZero() {
        assertEquals(List.of("0"), balances(AccountBalanceSeriesService.walkBack(null, Map.of(), TODAY, TODAY)));
    }

    @Test
    void theSeriesHasOnePointPerDayEndingTodayFromOneGroupedQuery() {
        account(AccountType.bank_account, "500");
        when(transactions.sumSignedByDateAfter(accountId, TODAY.minusDays(29))).thenReturn(List.of(
                day(TODAY, "200"), day(TODAY.minusDays(10), "-100")));

        List<BalancePointResponse> points = service.series(accountId, 30);

        assertEquals(30, points.size());
        assertEquals(TODAY.minusDays(29), points.get(0).date());
        assertEquals(TODAY, points.get(29).date());
        assertEquals(0, d("500").compareTo(points.get(29).balance()));
        assertEquals(0, d("300").compareTo(points.get(28).balance()));
        assertEquals(0, d("400").compareTo(points.get(18).balance()), "before the -100 day");
        assertEquals(0, d("300").compareTo(points.get(19).balance()), "end of the -100 day");
        // One grouped query for all 30 days, never one per day.
        verify(transactions, times(1)).sumSignedByDateAfter(accountId, TODAY.minusDays(29));
        verifyNoMoreInteractions(transactions);
    }

    @Test
    void aCardWalksBackOnItsSignedBalance() {
        account(AccountType.credit_card, "-2000");   // owes 2,000
        when(transactions.sumSignedByDateAfter(accountId, TODAY)).thenReturn(List.of(day(TODAY, "-500")));
        List<BalancePointResponse> points = service.series(accountId, 1);
        assertEquals(List.of("-2000"), balances(points));
        when(transactions.sumSignedByDateAfter(accountId, TODAY.minusDays(1))).thenReturn(List.of(day(TODAY, "-500")));
        assertEquals(List.of("-1500", "-2000"), balances(service.series(accountId, 2)));
    }

    @Test
    void aBrokerHasNoSeries() {
        account(AccountType.broker, "99999");
        assertTrue(service.series(accountId, 30).isEmpty());
        verify(transactions, never()).sumSignedByDateAfter(any(), any());
    }

    @Test
    void someoneElsesOrAMissingAccountIs404() {
        when(accounts.findOwnedAccount(accountId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.series(accountId, 30));
    }

    @Test
    void daysMustBeOneToThreeSixtyFive() {
        account(AccountType.bank_account, "1");
        when(transactions.sumSignedByDateAfter(any(), any())).thenReturn(List.of());
        assertThrows(ValidationException.class, () -> service.series(accountId, 0));
        assertThrows(ValidationException.class, () -> service.series(accountId, 366));
        assertEquals(1, service.series(accountId, 1).size());
        assertEquals(365, service.series(accountId, 365).size());
    }
}
