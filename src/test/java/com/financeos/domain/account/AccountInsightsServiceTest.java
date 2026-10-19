package com.financeos.domain.account;

import com.financeos.api.account.dto.AccountFlowSummaryResponse;
import com.financeos.api.account.dto.AccountPaymentsFromResponse;
import com.financeos.api.account.dto.BalancePointResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanChargeRepository;
import com.financeos.domain.loan.LoanEventRepository;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.statement.StatementRepository;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The window, balance average, quarter and EMI ordering rules of the account insights, with the repositories mocked. */
class AccountInsightsServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final UUID USER_ID = UUID.randomUUID();

    private final UUID accountId = UUID.randomUUID();
    private AccountRepository accounts;
    private TransactionRepository transactions;
    private AccountBalanceSeriesService balanceSeries;
    private LoanRepository loans;
    private LoanScheduleService loanSchedule;
    private AccountInsightsService service;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 10, 19).atStartOfDay(IST).toInstant(), IST));
        UserContext.setCurrentUserId(USER_ID);
        accounts = mock(AccountRepository.class);
        transactions = mock(TransactionRepository.class);
        balanceSeries = mock(AccountBalanceSeriesService.class);
        loans = mock(LoanRepository.class);
        loanSchedule = mock(LoanScheduleService.class);
        service = new AccountInsightsService(accounts, transactions, mock(StatementRepository.class),
                mock(CardholderRepository.class), balanceSeries, loans, mock(LoanEventRepository.class),
                mock(LoanPaymentRepository.class), mock(LoanChargeRepository.class), loanSchedule,
                mock(TransactionLinkRepository.class), mock(GmailProcessedMessageRepository.class));
        Account account = new Account("Savings", AccountType.bank_account);
        account.setId(accountId);
        when(accounts.findByIdAndUser_Id(accountId, USER_ID)).thenReturn(Optional.of(account));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
        UserContext.clear();
    }

    @Test
    void windowEndsTodayAndCoversTheRequestedDays() {
        AccountFlowSummaryResponse flow = service.flowSummary(accountId, 90);

        assertEquals(LocalDate.of(2026, 10, 19), flow.to());
        assertEquals(LocalDate.of(2026, 7, 22), flow.from());
        verify(transactions).sumByTypeBetween(accountId, LocalDate.of(2026, 7, 22), LocalDate.of(2026, 10, 19));
    }

    @Test
    void interestQuarterIsTheLastCompletedCalendarQuarter() {
        AccountFlowSummaryResponse flow = service.flowSummary(accountId, 90);

        assertEquals(LocalDate.of(2026, 7, 1), flow.interestFrom());
        assertEquals(LocalDate.of(2026, 9, 30), flow.interestTo());
    }

    @Test
    void interestQuarterRollsBackAYearInQ1() {
        AppTime.useClock(Clock.fixed(LocalDate.of(2026, 2, 3).atStartOfDay(IST).toInstant(), IST));

        AccountFlowSummaryResponse flow = service.flowSummary(accountId, 30);

        assertEquals(LocalDate.of(2025, 10, 1), flow.interestFrom());
        assertEquals(LocalDate.of(2025, 12, 31), flow.interestTo());
    }

    @Test
    void emptyAccountHasZeroFlowNoCategoriesAndZeroInterest() {
        when(transactions.sumInterestCreditsBetween(eq(accountId), any(), any())).thenReturn(BigDecimal.ZERO);

        AccountFlowSummaryResponse flow = service.flowSummary(accountId, 90);

        assertEquals(BigDecimal.ZERO, flow.moneyIn());
        assertEquals(BigDecimal.ZERO, flow.moneyOut());
        assertEquals(List.of(), flow.topInCategories());
        assertEquals(List.of(), flow.topOutCategories());
        assertEquals(BigDecimal.ZERO, flow.interestLastQuarter());
    }

    @Test
    void averageDailyBalanceIsNullForAnEmptySeries() {
        when(balanceSeries.series(accountId, 90)).thenReturn(List.of());

        assertNull(service.flowSummary(accountId, 90).averageDailyBalance());
    }

    @Test
    void averageDailyBalanceIsTheMeanOfTheSeriesRoundedHalfUp() {
        LocalDate d = LocalDate.of(2026, 10, 19);
        when(balanceSeries.series(accountId, 90)).thenReturn(List.of(
                new BalancePointResponse(d.minusDays(2), new BigDecimal("100.00")),
                new BalancePointResponse(d.minusDays(1), new BigDecimal("100.00")),
                new BalancePointResponse(d, new BigDecimal("100.01"))));

        // 300.01 / 3 = 100.00333...
        assertEquals(new BigDecimal("100.00"), service.flowSummary(accountId, 90).averageDailyBalance());

        when(balanceSeries.series(accountId, 90)).thenReturn(List.of(
                new BalancePointResponse(d.minusDays(1), new BigDecimal("0.00")),
                new BalancePointResponse(d, new BigDecimal("0.01"))));

        // 0.005 rounds up
        assertEquals(new BigDecimal("0.01"), service.flowSummary(accountId, 90).averageDailyBalance());
    }

    @Test
    void aYearAndADayAsksTheSeriesForItsMaximum() {
        when(balanceSeries.series(accountId, AccountBalanceSeriesService.MAX_DAYS)).thenReturn(List.of());

        service.flowSummary(accountId, 366);

        verify(balanceSeries).series(accountId, 365);
    }

    @Test
    void topCategoriesAreLimitedAndTiesBreakByName() {
        List<TransactionRepository.CategoryTotalProjection> credits = List.of(
                category("Gift", "200"), category("Salary", "5000"), category("Bonus", "200"), category("Refund", "10"));
        List<TransactionRepository.CategoryTotalProjection> debits = List.of(
                category("Fuel", "300"), category("Rent", "1000"), category("Food", "300"), category("Misc", "100"),
                category("Fees", "300"));
        when(transactions.sumByCategoryBetween(eq(accountId), eq(TransactionType.CREDIT), any(), any())).thenReturn(credits);
        when(transactions.sumByCategoryBetween(eq(accountId), eq(TransactionType.DEBIT), any(), any())).thenReturn(debits);

        AccountFlowSummaryResponse flow = service.flowSummary(accountId, 90);

        assertEquals(List.of("Salary", "Bonus"), flow.topInCategories());
        assertEquals(List.of("Rent", "Fees", "Food"), flow.topOutCategories());
    }

    @Test
    void daysOutOfRangeAreRejected() {
        assertThrows(ValidationException.class, () -> service.flowSummary(accountId, 0));
        assertThrows(ValidationException.class, () -> service.flowSummary(accountId, 367));
    }

    @Test
    void anotherUsersAccountIsNotFoundForEveryRead() {
        UUID foreign = UUID.randomUUID();
        when(accounts.findByIdAndUser_Id(foreign, USER_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.tabCounts(foreign));
        assertThrows(ResourceNotFoundException.class, () -> service.flowSummary(foreign, 90));
        assertThrows(ResourceNotFoundException.class, () -> service.paymentsFrom(foreign));
        assertThrows(ResourceNotFoundException.class, () -> service.gmailSummary(foreign));
    }

    @Test
    void emisAreOrderedByNextDueDateWithNullsLastThenByName() {
        LocalDate soon = LocalDate.of(2026, 10, 25);
        LocalDate later = LocalDate.of(2026, 11, 25);
        Loan zeta = loan("Zeta");
        Loan alpha = loan("Alpha");
        Loan done = loan("Done");
        Loan beta = loan("Beta");
        Loan early = loan("Early");
        when(loans.findByPaymentAccount_IdAndStatus(accountId, LoanStatus.active))
                .thenReturn(List.of(zeta, done, alpha, beta, early));
        schedule(zeta, later);
        schedule(alpha, later);
        schedule(done, null);
        schedule(beta, soon);
        schedule(early, soon);

        List<AccountPaymentsFromResponse.Emi> emis = service.paymentsFrom(accountId).emis();

        assertEquals(List.of("Beta", "Early", "Alpha", "Zeta", "Done"),
                emis.stream().map(AccountPaymentsFromResponse.Emi::name).toList());
        assertEquals(new BigDecimal("4500.00"), emis.get(0).amount());
        assertNull(emis.get(4).nextDueDate());
        verify(loans).findByPaymentAccount_IdAndStatus(accountId, LoanStatus.active);
    }

    private Loan loan(String name) {
        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName(name);
        return loan;
    }

    private void schedule(Loan loan, LocalDate nextDue) {
        ScheduleResult result = new ScheduleResult(List.of(), BigDecimal.ZERO, new BigDecimal("4500.00"), BigDecimal.ZERO,
                0, 0, nextDue, null, BigDecimal.ZERO, BigDecimal.ZERO, null);
        when(loanSchedule.compute(eq(loan), any(), any(), any())).thenReturn(result);
    }

    private TransactionRepository.CategoryTotalProjection category(String name, String total) {
        TransactionRepository.CategoryTotalProjection row = mock(TransactionRepository.CategoryTotalProjection.class);
        when(row.getName()).thenReturn(name);
        when(row.getTotal()).thenReturn(new BigDecimal(total));
        return row;
    }
}
