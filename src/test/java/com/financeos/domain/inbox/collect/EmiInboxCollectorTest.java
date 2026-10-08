package com.financeos.domain.inbox.collect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.loan.schedule.ScheduleResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmiInboxCollectorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final LoanService loanService = mock(LoanService.class);
    private final EmiInboxCollector collector = new EmiInboxCollector(loanService);
    private final UUID userId = UUID.randomUUID();
    private Loan loan;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 5).atZone(IST).toInstant(), IST));
        loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Home loan");
        loan.setStatus(LoanStatus.active);
        loan.setCreatedAt(TODAY.minusDays(400).atStartOfDay(IST).toInstant());
        Account savings = new Account();
        savings.setId(UUID.randomUUID());
        savings.setName("HDFC Savings");
        loan.setPaymentAccount(savings);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private static InstallmentDto installment(int seq, LocalDate due, String status) {
        return new InstallmentDto(seq, due, new BigDecimal("100000"), new BigDecimal("25000"), new BigDecimal("1000"),
                new BigDecimal("24000"), new BigDecimal("76000"), status, null);
    }

    /** Twelve monthly installments whose first unsettled one (seq 4) falls {@code daysUntilCurrent} days from today. */
    private static List<InstallmentDto> scheduleWithCurrentIn(int daysUntilCurrent) {
        List<InstallmentDto> list = new ArrayList<>();
        LocalDate current = TODAY.plusDays(daysUntilCurrent);
        for (int seq = 1; seq <= 12; seq++) {
            LocalDate due = current.plusMonths(seq - 4L);
            list.add(installment(seq, due, seq < 4 ? "settled" : due.isBefore(TODAY) ? "overdue" : "upcoming"));
        }
        return list;
    }

    private List<InboxItemResponse> collect(List<InstallmentDto> installments) {
        ScheduleResult schedule = new ScheduleResult(installments, BigDecimal.TEN, new BigDecimal("25000"), BigDecimal.ZERO,
                installments.size(), 3, null, null, BigDecimal.ZERO, BigDecimal.ZERO, null);
        when(loanService.getAllLoansWithSchedule()).thenReturn(List.of(new LoanService.LoanWithSchedule(loan, schedule)));
        return collector.collect(userId, TODAY);
    }

    @Test
    void currentInstallmentDueWithinAWeekIsAWarningRowLinkingToTheInstallment() {
        InboxItemResponse row = collect(scheduleWithCurrentIn(3)).get(0);

        String href = "/loans/" + loan.getId() + "?installment=4";
        assertEquals("emi:" + loan.getId() + ":4", row.key(), "the first unsettled installment, not a later one");
        assertEquals("emi", row.kind());
        assertEquals(InboxItemResponse.ROW_ITEM, row.rowType());
        assertEquals("warning", row.severity());
        assertEquals("act_now", row.section());
        assertEquals("Home loan: EMI #4", row.title());
        assertEquals("Debits in 3 days · 23 Oct from HDFC Savings", row.subtitle());
        assertEquals(href, row.href());
        assertEquals(new BigDecimal("25000"), row.amount());
        assertEquals(TODAY.plusDays(3), row.date());
        assertEquals(List.of(InboxRows.open(href), InboxRows.snooze()), row.actions());
        assertEquals(InboxRefsResponse.ofLoan(loan.getId()), row.refs());
    }

    @Test
    void debitsTodayAndTomorrowReadNaturally() {
        assertEquals("Debits today · 20 Oct from HDFC Savings", collect(scheduleWithCurrentIn(0)).get(0).subtitle());
        assertEquals("Debits tomorrow · 21 Oct from HDFC Savings", collect(scheduleWithCurrentIn(1)).get(0).subtitle());
    }

    @Test
    void sevenDaysOutIsListedAndEightDaysOutIsNot() {
        assertEquals(1, collect(scheduleWithCurrentIn(7)).size());
        assertEquals(List.of(), collect(scheduleWithCurrentIn(8)));
    }

    @Test
    void theSourceAccountIsNamedOnlyWhenKnown() {
        loan.setPaymentAccount(null);
        assertEquals("Debits in 2 days · 22 Oct", collect(scheduleWithCurrentIn(2)).get(0).subtitle());

        Account unnamed = new Account();
        unnamed.setId(UUID.randomUUID());
        loan.setPaymentAccount(unnamed);
        assertEquals("Debits in 2 days · 22 Oct", collect(scheduleWithCurrentIn(2)).get(0).subtitle());
    }

    @Test
    void overdueInstallmentIsCriticalAndAsksForThePaymentToBeRecorded() {
        InboxItemResponse row = collect(scheduleWithCurrentIn(-5)).get(0);
        assertEquals("critical", row.severity());
        assertEquals("act_now", row.section());
        assertEquals("Overdue by 5 days · was due 15 Oct · record the payment once it's done", row.subtitle());
        assertEquals(TODAY.minusDays(5), row.date());
    }

    @Test
    void staleOverdueInstallmentsStayOut() {
        assertEquals(1, collect(scheduleWithCurrentIn(-45)).size(), "45 days overdue is still live");
        assertEquals(List.of(), collect(scheduleWithCurrentIn(-46)), "older than 45 days: nobody is recording this loan");

        loan.setCreatedAt(TODAY.minusDays(2).atStartOfDay(IST).toInstant());
        assertEquals(List.of(), collect(scheduleWithCurrentIn(-5)), "due before the loan was entered: backfilled history");
    }

    @Test
    void mutedLoansStayOutButAnUnsetMuteFlagDoesNot() {
        loan.setNotificationsMuted(true);
        assertEquals(List.of(), collect(scheduleWithCurrentIn(2)));

        loan.setNotificationsMuted(null);
        assertEquals(1, collect(scheduleWithCurrentIn(2)).size());
    }

    @Test
    void closedAndForeclosedLoansStayOut() {
        loan.setStatus(LoanStatus.closed);
        assertEquals(List.of(), collect(scheduleWithCurrentIn(2)));
        loan.setStatus(LoanStatus.foreclosed);
        assertEquals(List.of(), collect(scheduleWithCurrentIn(2)));
    }

    @Test
    void aFullySettledScheduleOrAnUndatedInstallmentHasNothingToShow() {
        assertEquals(List.of(), collect(List.of(installment(1, TODAY.minusMonths(1), "settled"), installment(2, TODAY, "settled"))));
        assertEquals(List.of(), collect(List.of(installment(1, null, "upcoming"))));
    }
}
