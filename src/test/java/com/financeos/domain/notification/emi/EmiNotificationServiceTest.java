package com.financeos.domain.notification.emi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.bill.BillNotificationKinds;
import com.financeos.domain.notification.push.PushMessage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EmiNotificationServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final List<Integer> OFFSETS = List.of(7, 3, 1, 0);

    private final LoanService loanService = mock(LoanService.class);
    private final LoanRepository loanRepository = mock(LoanRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final EmiNotificationService service = new EmiNotificationService(loanService, loanRepository, prefsLoader, settingsService);

    private final UUID userId = UUID.randomUUID();
    private final UserNotificationSettings settings = new UserNotificationSettings(userId);
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));
    private Loan loan;

    @BeforeEach
    void setUp() {
        clockAt(10);
        loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Home loan");
        loan.setStatus(LoanStatus.active);
        loan.setCreatedAt(TODAY.minusDays(400).atStartOfDay(IST).toInstant());
        Account savings = new Account();
        savings.setId(UUID.randomUUID());
        savings.setName("HDFC Savings");
        loan.setPaymentAccount(savings);
        when(settingsService.deliver(any(), any())).thenReturn(1);
        prefs(true);
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void clockAt(int hour) {
        AppTime.useClock(Clock.fixed(TODAY.atTime(hour, 5).atZone(IST).toInstant(), IST));
    }

    private void prefs(boolean canSend) {
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(settings, canSend, 9, OFFSETS, kinds));
    }

    private static InstallmentDto installment(int seq, LocalDate due, String status) {
        return new InstallmentDto(seq, due, new BigDecimal("100000"), new BigDecimal("25000"), new BigDecimal("1000"),
                new BigDecimal("24000"), new BigDecimal("76000"), status, null);
    }

    /** A 12-installment loan whose first unsettled installment (seq 4) falls {@code daysUntilCurrent} days from today. */
    private List<InstallmentDto> scheduleWithCurrentIn(int daysUntilCurrent) {
        List<InstallmentDto> list = new ArrayList<>();
        LocalDate current = TODAY.plusDays(daysUntilCurrent);
        for (int seq = 1; seq <= 12; seq++) {
            LocalDate due = current.plusMonths(seq - 4L);
            list.add(installment(seq, due, seq < 4 ? "settled" : due.isBefore(TODAY) ? "overdue" : "upcoming"));
        }
        return list;
    }

    private void loanWith(List<InstallmentDto> installments) {
        ScheduleResult schedule = new ScheduleResult(installments, BigDecimal.TEN, new BigDecimal("25000"), BigDecimal.ZERO,
                installments.size(), 3, null, null, BigDecimal.ZERO, BigDecimal.ZERO, null);
        when(loanService.getAllLoansWithSchedule()).thenReturn(List.of(new LoanService.LoanWithSchedule(loan, schedule)));
    }

    private PushMessage delivered() {
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(settingsService).deliver(eq(settings), message.capture());
        return message.getValue();
    }

    // ---------------------------------------------------------------- tick behaviour

    @Test
    void waitsForTheSendHour() {
        clockAt(8);
        loanWith(scheduleWithCurrentIn(0));
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(loanService, never()).getAllLoansWithSchedule();
    }

    @Test
    void reminderFiresAtTheSmallestReachedOffsetAndRecordsTheInstallment() {
        loanWith(scheduleWithCurrentIn(3));

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));

        PushMessage message = delivered();
        assertEquals("Home loan: EMI debits in 3 days", message.title());
        assertEquals("₹25,000 on 23 Oct from HDFC Savings · #4 of 12", message.body());
        assertEquals("/loans/" + loan.getId() + "?installment=4", message.url());
        assertEquals("emi-" + loan.getId(), message.tag());
        assertEquals(4, loan.getLastNotifiedSeq());
        assertEquals("DUE_3", loan.getLastNotifiedKind());
        assertEquals(TODAY, loan.getLastNotifiedOn());
        verify(loanRepository).save(loan);
    }

    @Test
    void reminderOutsideEveryOffsetIsSilent() {
        loanWith(scheduleWithCurrentIn(12));
        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        verify(settingsService, never()).deliver(any(), any());
        assertNull(loan.getLastNotifiedKind());
    }

    @Test
    void sameOffsetIsNotRepeatedButALaterOneIs() {
        loan.setLastNotifiedSeq(4);
        loan.setLastNotifiedKind("DUE_3");
        loan.setLastNotifiedOn(TODAY.minusDays(1));
        loanWith(scheduleWithCurrentIn(3));
        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));

        loanWith(scheduleWithCurrentIn(1));
        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        assertEquals("DUE_1", loan.getLastNotifiedKind());
        assertEquals("Home loan: EMI debits tomorrow", delivered().title());
    }

    @Test
    void dueTodayPhrase() {
        loanWith(scheduleWithCurrentIn(0));
        service.evaluate(userId);
        assertEquals("Home loan: EMI debits today", delivered().title());
    }

    @Test
    void markerFromAPreviousInstallmentIsIgnored() {
        loan.setLastNotifiedSeq(3);
        loan.setLastNotifiedKind("OVERDUE");
        loan.setLastNotifiedOn(TODAY.minusDays(1));
        loanWith(scheduleWithCurrentIn(7));

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        assertEquals(4, loan.getLastNotifiedSeq());
        assertEquals("DUE_7", loan.getLastNotifiedKind());
    }

    @Test
    void overdueFiresOnceThenWeekly() {
        loanWith(scheduleWithCurrentIn(-2));
        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        PushMessage message = delivered();
        assertEquals("Home loan: EMI overdue by 2 days", message.title());
        assertEquals("₹25,000 was due 18 Oct. Record the payment once it's done. · #4 of 12", message.body());
        assertEquals("OVERDUE", loan.getLastNotifiedKind());

        // the next day: nothing
        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));

        // a week after the last nag: again
        loan.setLastNotifiedOn(TODAY.minusDays(7));
        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        assertEquals(TODAY, loan.getLastNotifiedOn());
    }

    @Test
    void overdueFollowsADueReminderForTheSameInstallment() {
        loan.setLastNotifiedSeq(4);
        loan.setLastNotifiedKind("DUE_0");
        loan.setLastNotifiedOn(TODAY.minusDays(1));
        loanWith(scheduleWithCurrentIn(-1));

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        assertEquals("Home loan: EMI overdue by 1 day", delivered().title());
    }

    @Test
    void longOverdueInstallmentsAreHistoryNotNags() {
        loanWith(scheduleWithCurrentIn(-46));
        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void installmentsDueBeforeTheLoanWasEnteredAreBackfill() {
        loan.setCreatedAt(TODAY.minusDays(1).atStartOfDay(IST).toInstant());
        loanWith(scheduleWithCurrentIn(-5));
        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void mutedLoanRecordsTheMarkerButNeverPushes() {
        loan.setNotificationsMuted(true);
        loanWith(scheduleWithCurrentIn(1));

        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        assertEquals("DUE_1", loan.getLastNotifiedKind());
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void kindSwitchesAreHonouredPerKind() {
        kinds.put(NotificationKind.EMI_DUE_REMINDER, false);
        prefs(true);
        loanWith(scheduleWithCurrentIn(1));
        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));

        loanWith(scheduleWithCurrentIn(-1));
        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId), "overdue is a separate switch");
    }

    @Test
    void pushOffStillAdvancesTheMarker() {
        prefs(false);
        loanWith(scheduleWithCurrentIn(0));
        assertEquals(new NotificationOutcome(1, 1, 0), service.evaluate(userId));
        assertEquals("DUE_0", loan.getLastNotifiedKind());
    }

    @Test
    void closedLoansAndFullySettledLoansAreSkipped() {
        loan.setStatus(LoanStatus.closed);
        loanWith(scheduleWithCurrentIn(0));
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));

        loan.setStatus(LoanStatus.active);
        List<InstallmentDto> settled = List.of(installment(1, TODAY.minusDays(30), "settled"), installment(2, TODAY, "settled"));
        loanWith(settled);
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
    }

    @Test
    void bodyWithoutAPaymentAccount() {
        loan.setPaymentAccount(null);
        loanWith(scheduleWithCurrentIn(7));
        service.evaluate(userId);
        assertEquals("₹25,000 on 27 Oct · #4 of 12", delivered().body());
    }

    // ---------------------------------------------------------------- pure helpers

    @Test
    void applicableKindPicksTheSmallestReachedOffset() {
        assertEquals("DUE_7", EmiNotificationService.applicableKind(5, OFFSETS));
        assertEquals("DUE_3", EmiNotificationService.applicableKind(3, OFFSETS));
        assertEquals("DUE_0", EmiNotificationService.applicableKind(0, OFFSETS));
        assertEquals(BillNotificationKinds.OVERDUE, EmiNotificationService.applicableKind(-1, OFFSETS));
        assertNull(EmiNotificationService.applicableKind(8, OFFSETS));
    }

    @Test
    void shouldSendFollowsTheSequence() {
        assertTrue(EmiNotificationService.shouldSend("DUE_7", null, null, TODAY));
        assertTrue(EmiNotificationService.shouldSend("DUE_3", "DUE_7", TODAY, TODAY));
        assertFalse(EmiNotificationService.shouldSend("DUE_7", "DUE_3", TODAY, TODAY));
        assertTrue(EmiNotificationService.shouldSend("OVERDUE", "DUE_0", TODAY, TODAY));
        assertFalse(EmiNotificationService.shouldSend("OVERDUE", "OVERDUE", TODAY.minusDays(6), TODAY));
        assertTrue(EmiNotificationService.shouldSend("OVERDUE", "OVERDUE", TODAY.minusDays(7), TODAY));
        assertTrue(EmiNotificationService.shouldSend("OVERDUE", "OVERDUE", null, TODAY));
    }

    @Test
    void preferenceMapping() {
        assertEquals(NotificationKind.EMI_OVERDUE, EmiNotificationService.preferenceFor("OVERDUE"));
        assertEquals(NotificationKind.EMI_DUE_REMINDER, EmiNotificationService.preferenceFor("DUE_3"));
        assertNull(EmiNotificationService.preferenceFor("PAID"));
    }

    @Test
    void currentInstallmentIsTheFirstUnsettledOne() {
        List<InstallmentDto> schedule = scheduleWithCurrentIn(2);
        assertEquals(4, EmiNotificationService.currentInstallment(schedule).seq());
        assertNull(EmiNotificationService.currentInstallment(List.of()));
    }
}
