package com.financeos.domain.notification.reminder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.core.time.AppTime;
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
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.notification.bill.BillStatus;
import com.financeos.domain.notification.bill.CardBill;
import com.financeos.domain.notification.bill.CardBillService;
import com.financeos.domain.notification.bill.PaidSource;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.NotificationFeedService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReminderFeedServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final List<String> KINDS = List.of(AppNotificationKind.BILL_REMINDER, AppNotificationKind.EMI_REMINDER);

    private final CardBillService cardBillService = mock(CardBillService.class);
    private final LoanService loanService = mock(LoanService.class);
    private final LoanRepository loanRepository = mock(LoanRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationFeedService feedService = mock(NotificationFeedService.class);
    private ReminderFeedService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID cardId = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private final Map<NotificationKind, Boolean> kinds = new EnumMap<>(NotificationSettingsCodec.parseKinds(null));
    private Loan loan;

    @BeforeEach
    void setUp() {
        clockAt(10);
        service = new ReminderFeedService(cardBillService, loanService, loanRepository, prefsLoader, feedService);
        loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Home loan");
        loan.setStatus(LoanStatus.active);
        loan.setCreatedAt(TODAY.minusDays(400).atStartOfDay(IST).toInstant());
        when(feedService.upsertAll(any(), any())).thenReturn(true);
        prefs(false); // a user push cannot reach: the feed rows are for everyone
        bills();
        loans(List.of());
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void clockAt(int hour) {
        AppTime.useClock(Clock.fixed(TODAY.atTime(hour, 5).atZone(IST).toInstant(), IST));
    }

    private void prefs(boolean canSend) {
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(new UserNotificationSettings(userId), canSend, 9, List.of(7, 3, 1, 0), kinds));
    }

    private CardBill bill(BillStatus status, long daysUntilDue) {
        LocalDate due = TODAY.plusDays(daysUntilDue);
        Instant created = due.minusDays(20).atStartOfDay(IST).toInstant();
        return new CardBill(cardId, "HDFC Regalia", "4321", statementId, due.minusDays(50), due.minusDays(20), due,
                new BigDecimal("12340"), null, BigDecimal.ZERO, new BigDecimal("12340"), PaidSource.NONE, status,
                daysUntilDue, null, false, created, null, null, null, null, null);
    }

    private void bills(CardBill... bills) {
        when(cardBillService.listBills(userId)).thenReturn(List.of(bills));
    }

    private static InstallmentDto installment(int seq, LocalDate due, String status) {
        return new InstallmentDto(seq, due, new BigDecimal("100000"), new BigDecimal("25000"), new BigDecimal("1000"),
                new BigDecimal("24000"), new BigDecimal("76000"), status, null);
    }

    private void loans(List<InstallmentDto> installments) {
        ScheduleResult schedule = new ScheduleResult(installments, BigDecimal.TEN, new BigDecimal("25000"), BigDecimal.ZERO,
                installments.size(), 0, null, null, BigDecimal.ZERO, BigDecimal.ZERO, null);
        List<LoanService.LoanWithSchedule> all = List.of(new LoanService.LoanWithSchedule(loan, schedule));
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Predicate<Loan> filter = inv.getArgument(0);
            return all.stream().filter(e -> filter.test(e.loan())).toList();
        }).when(loanService).getLoansWithSchedule(any());
    }

    @SuppressWarnings("unchecked")
    private List<NotificationFeedService.Reminder> upserted() {
        ArgumentCaptor<List<NotificationFeedService.Reminder>> captor = ArgumentCaptor.forClass(List.class);
        verify(feedService).upsertAll(eq(userId), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private Set<String> kept() {
        ArgumentCaptor<Set<String>> captor = ArgumentCaptor.forClass(Set.class);
        verify(feedService).resolveAllExcept(eq(userId), eq(KINDS), captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- who gets rows, and when

    @Test
    void aUserWithoutPushStillGetsTheBillRow() {
        bills(bill(BillStatus.OPEN, 1));

        NotificationOutcome outcome = service.evaluate(userId);

        assertEquals(new NotificationOutcome(1, 1, 0), outcome);
        NotificationFeedService.Reminder r = upserted().get(0);
        assertEquals("bill:" + statementId, r.dedupeKey());
        assertEquals(AppNotificationKind.BILL_REMINDER, r.kind());
        assertEquals("HDFC Regalia bill due tomorrow", r.title());
        assertEquals("₹12,340 due 5 Nov", r.body());
        assertEquals("D1", r.stage());
        assertEquals(cardId, r.refs().accountId());
        assertTrue(service.runsWithoutPush());
    }

    @Test
    void aUserWithNoSettingsRowGetsRowsToo() {
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(null, false, 9, List.of(7, 3, 1, 0), NotificationSettingsCodec.parseKinds(null)));
        bills(bill(BillStatus.OPEN, 0));

        service.evaluate(userId);

        assertEquals("D0", upserted().get(0).stage());
    }

    @Test
    void waitsForTheSendHourAndRunsOncePerDay() {
        clockAt(8);
        bills(bill(BillStatus.OPEN, 1));
        assertEquals(NotificationOutcome.NONE, service.evaluate(userId));
        verify(cardBillService, never()).listBills(any(UUID.class));

        clockAt(9);
        service.evaluate(userId);
        service.evaluate(userId);
        verify(feedService, times(1)).upsertAll(eq(userId), any());

        AppTime.useClock(Clock.fixed(TODAY.plusDays(1).atTime(9, 5).atZone(IST).toInstant(), IST));
        service.evaluate(userId);
        verify(feedService, times(2)).upsertAll(eq(userId), any());
    }

    @Test
    void theTickVisitsUsersWhoOnlyHaveALoan() {
        UUID other = UUID.randomUUID();
        when(loanRepository.findDistinctUserIdsByStatus(LoanStatus.active)).thenReturn(List.of(other));
        assertEquals(List.of(other), List.copyOf(service.extraFeedUserIds()));
    }

    // ---------------------------------------------------------------- stages

    @Test
    void billStagesPerDaysLeft() {
        bills(bill(BillStatus.OPEN, 3));
        service.evaluate(userId);
        assertEquals("HDFC Regalia bill due in 3 days", upserted().get(0).title());
    }

    @Test
    void aBillMoreThanThreeDaysOutHasNoRow() {
        bills(bill(BillStatus.OPEN, 4));

        service.evaluate(userId);

        assertTrue(upserted().isEmpty());
    }

    @Test
    void anOverdueBillShowsHowLate() {
        bills(bill(BillStatus.OVERDUE, -5));

        service.evaluate(userId);

        NotificationFeedService.Reminder r = upserted().get(0);
        assertEquals("HDFC Regalia bill overdue by 5 days", r.title());
        assertEquals("OVERDUE", r.stage());
    }

    @Test
    void aPaidOrStaleBillIsNotInTheKeepSetSoItsRowIsResolved() {
        bills(bill(BillStatus.PAID, 1));

        service.evaluate(userId);

        assertTrue(upserted().isEmpty());
        assertTrue(kept().isEmpty(), "everything of the user's still in the feed is resolved: the bill was paid");
    }

    @Test
    void anOverdueBillIngestedLongAfterItsDueDateIsStaleAndGetsNoRow() {
        CardBill stale = new CardBill(cardId, "HDFC Regalia", "4321", statementId, TODAY.minusDays(100), TODAY.minusDays(70),
                TODAY.minusDays(40), new BigDecimal("500"), null, BigDecimal.ZERO, new BigDecimal("500"), PaidSource.NONE,
                BillStatus.OVERDUE, -40L, null, false, TODAY.minusDays(2).atStartOfDay(IST).toInstant(), null, null, null, null, null);
        bills(stale);

        service.evaluate(userId);

        assertTrue(upserted().isEmpty());
    }

    // ---------------------------------------------------------------- EMIs

    @Test
    void everyUnpaidInstallmentInTheWindowGetsItsOwnRowWithALoanRef() {
        loans(List.of(
                installment(1, TODAY.minusMonths(3), "settled"),
                installment(2, TODAY.minusDays(2), "overdue"),
                installment(3, TODAY.plusDays(2), "upcoming"),
                installment(4, TODAY.plusDays(32), "upcoming")));

        NotificationOutcome outcome = service.evaluate(userId);

        assertEquals(2, outcome.recorded());
        List<NotificationFeedService.Reminder> rows = upserted();
        assertEquals("emi:" + loan.getId() + ":2", rows.get(0).dedupeKey());
        assertEquals("Home loan EMI overdue by 2 days", rows.get(0).title());
        assertEquals("OVERDUE", rows.get(0).stage());
        assertEquals("emi:" + loan.getId() + ":3", rows.get(1).dedupeKey());
        assertEquals("Home loan EMI due in 2 days", rows.get(1).title());
        assertEquals("₹25,000 due 6 Nov · #3 of 4", rows.get(1).body());
        assertEquals(loan.getId(), rows.get(1).refs().loanId());
        assertEquals(AppNotificationKind.EMI_REMINDER, rows.get(1).kind());
    }

    @Test
    void aSettledInstallmentHasNoRowSoItsExistingRowIsResolved() {
        loans(List.of(installment(1, TODAY.plusDays(1), "settled")));

        service.evaluate(userId);

        assertTrue(upserted().isEmpty());
        assertTrue(kept().isEmpty());
    }

    @Test
    void installmentsOverdueBeyondTheStaleLimitAreSkipped() {
        loans(List.of(installment(1, TODAY.minusDays(46), "overdue")));

        service.evaluate(userId);

        assertTrue(upserted().isEmpty());
    }

    // ---------------------------------------------------------------- switches

    @Test
    void theDueReminderSwitchOffMeansNoDueRows() {
        kinds.put(NotificationKind.BILL_DUE_REMINDER, false);
        prefs(false);
        bills(bill(BillStatus.OPEN, 1));
        service.evaluate(userId);
        assertTrue(upserted().isEmpty());
    }

    @Test
    void theOverdueSwitchOffMeansNoOverdueRows() {
        kinds.put(NotificationKind.BILL_OVERDUE, false);
        kinds.put(NotificationKind.EMI_OVERDUE, false);
        prefs(false);
        bills(bill(BillStatus.OVERDUE, -2));
        loans(List.of(installment(1, TODAY.minusDays(3), "overdue")));

        service.evaluate(userId);

        assertTrue(upserted().isEmpty());
        assertTrue(kept().isEmpty());
    }

    @Test
    void theEmiSwitchesOffMeansNoEmiRowsButBillRowsRemain() {
        kinds.put(NotificationKind.EMI_DUE_REMINDER, false);
        kinds.put(NotificationKind.EMI_OVERDUE, false);
        prefs(true);
        bills(bill(BillStatus.OPEN, 2));
        loans(List.of(installment(1, TODAY.plusDays(1), "upcoming")));

        service.evaluate(userId);

        List<NotificationFeedService.Reminder> rows = upserted();
        assertEquals(1, rows.size());
        assertEquals(AppNotificationKind.BILL_REMINDER, rows.get(0).kind());
    }

    // ---------------------------------------------------------------- tenancy and failures

    @Test
    void everyReadAndWriteIsForTheGivenUserOnly() {
        UUID other = UUID.randomUUID();
        when(prefsLoader.load(other)).thenReturn(new NotificationPrefs(null, false, 9, List.of(0), NotificationSettingsCodec.parseKinds(null)));
        bills(bill(BillStatus.OPEN, 1));

        service.evaluate(userId);

        verify(cardBillService).listBills(userId);
        verify(cardBillService, never()).listBills(other);
        verify(feedService, never()).upsertAll(eq(other), any());
        verify(feedService, never()).resolveAllExcept(eq(other), any(), any());
    }

    @Test
    void whenTheBillsCannotBeReadNothingIsResolvedAndTheDayIsNotConsumed() {
        when(cardBillService.listBills(userId)).thenThrow(new IllegalStateException("db down"));
        try {
            service.evaluate(userId);
        } catch (IllegalStateException expected) {
            // the scheduler logs it and keeps going
        }
        verify(feedService, never()).resolveAllExcept(any(), any(), any());
        org.mockito.Mockito.doReturn(List.of(bill(BillStatus.OPEN, 1))).when(cardBillService).listBills(userId);
        service.evaluate(userId);
        verify(feedService).upsertAll(eq(userId), any());
    }

    @Test
    void aFailedUpsertOrResolveDoesNotConsumeTheDaySoItRetriesToday() {
        bills(bill(BillStatus.OPEN, 1));
        when(feedService.upsertAll(eq(userId), any())).thenReturn(false);
        service.evaluate(userId);
        when(feedService.upsertAll(eq(userId), any())).thenReturn(true);
        when(feedService.resolveAllExcept(eq(userId), any(), any())).thenReturn(-1);
        service.evaluate(userId);
        verify(feedService, times(2)).upsertAll(eq(userId), any());

        when(feedService.resolveAllExcept(eq(userId), any(), any())).thenReturn(0);
        service.evaluate(userId);
        service.evaluate(userId);
        verify(feedService, times(3)).upsertAll(eq(userId), any());
    }
}
