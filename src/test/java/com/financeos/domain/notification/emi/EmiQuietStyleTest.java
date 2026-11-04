package com.financeos.domain.notification.emi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.ReminderStyle;
import com.financeos.domain.notification.UserNotificationSettings;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Quiet reminder style on the EMI push: one push, no overdue re-nags. */
class EmiQuietStyleTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    private final LoanService loanService = mock(LoanService.class);
    private final LoanRepository loanRepository = mock(LoanRepository.class);
    private final NotificationPrefsLoader prefsLoader = mock(NotificationPrefsLoader.class);
    private final NotificationSettingsService settingsService = mock(NotificationSettingsService.class);
    private final EmiNotificationService service = new EmiNotificationService(loanService, loanRepository, prefsLoader, settingsService);

    private final UUID userId = UUID.randomUUID();
    private final UserNotificationSettings settings = new UserNotificationSettings(userId);
    private Loan loan;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 5).atZone(IST).toInstant(), IST));
        loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Home loan");
        loan.setStatus(LoanStatus.active);
        loan.setCreatedAt(TODAY.minusDays(400).atStartOfDay(IST).toInstant());
        settings.setReminderStyle(ReminderStyle.QUIET.name());
        when(settingsService.deliver(any(), any())).thenReturn(1);
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(settings, true, 9, List.of(7, 3, 1, 0),
                NotificationSettingsCodec.parseKinds(null)));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void currentInstallmentIn(int days) {
        LocalDate due = TODAY.plusDays(days);
        List<InstallmentDto> installments = List.of(new InstallmentDto(1, due, new BigDecimal("100000"), new BigDecimal("25000"),
                new BigDecimal("1000"), new BigDecimal("24000"), new BigDecimal("76000"), due.isBefore(TODAY) ? "overdue" : "upcoming", null));
        ScheduleResult schedule = new ScheduleResult(installments, BigDecimal.TEN, new BigDecimal("25000"), BigDecimal.ZERO,
                1, 0, null, null, BigDecimal.ZERO, BigDecimal.ZERO, null);
        List<LoanService.LoanWithSchedule> all = List.of(new LoanService.LoanWithSchedule(loan, schedule));
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Predicate<Loan> filter = inv.getArgument(0);
            return all.stream().filter(e -> filter.test(e.loan())).toList();
        }).when(loanService).getLoansWithSchedule(any());
    }

    @Test
    void anInstallmentFiveDaysOutIsNotPushed() {
        currentInstallmentIn(5);

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        verify(settingsService, never()).deliver(any(), any());
    }

    @Test
    void theOneDayOffsetPushesOnceAndTheDueDayStaysSilent() {
        currentInstallmentIn(1);
        assertEquals(1, service.evaluate(userId).sent());

        currentInstallmentIn(0);
        assertEquals(0, service.evaluate(userId).sent());

        verify(settingsService, times(1)).deliver(any(), any());
    }

    @Test
    void anInstallmentFirstSeenOnTheDueDayStillGetsItsOnePush() {
        currentInstallmentIn(0);

        assertEquals(1, service.evaluate(userId).sent());
    }

    @Test
    void overdueNeverPushes() {
        currentInstallmentIn(-3);

        NotificationOutcome outcome = service.evaluate(userId);

        assertEquals(new NotificationOutcome(1, 1, 0), outcome);
        verify(settingsService, never()).deliver(any(), any());
    }
}
