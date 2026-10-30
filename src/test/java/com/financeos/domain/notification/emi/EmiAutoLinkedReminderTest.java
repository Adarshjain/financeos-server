package com.financeos.domain.notification.emi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.time.AppTime;
import com.financeos.domain.account.Account;
import com.financeos.domain.loan.Loan;
import com.financeos.domain.loan.LoanPayment;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.loan.LoanStatus;
import com.financeos.domain.loan.LoanType;
import com.financeos.domain.loan.RateType;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.notification.NotificationKind;
import com.financeos.domain.notification.NotificationOutcome;
import com.financeos.domain.notification.NotificationPrefs;
import com.financeos.domain.notification.NotificationPrefsLoader;
import com.financeos.domain.notification.NotificationSettingsCodec;
import com.financeos.domain.notification.NotificationSettingsService;
import com.financeos.domain.notification.UserNotificationSettings;
import com.financeos.domain.transaction.link.LinkOrigin;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An overdue installment settled by an auto-linked debit no longer produces an EMI_OVERDUE reminder. */
class EmiAutoLinkedReminderTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

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
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 5).atZone(IST).toInstant(), IST));
        loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setName("Home loan");
        loan.setLoanType(LoanType.home);
        loan.setPrincipal(new BigDecimal("120000"));
        loan.setAnnualRatePct(new BigDecimal("12"));
        loan.setRateType(RateType.fixed);
        loan.setTenureMonths(12);
        loan.setStartDate(TODAY.minusDays(32));
        loan.setFirstEmiDate(TODAY.minusDays(2)); // installment 1 fell due two days ago
        loan.setStatus(LoanStatus.active);
        loan.setCreatedAt(TODAY.minusDays(400).atStartOfDay(IST).toInstant());
        Account savings = new Account();
        savings.setId(UUID.randomUUID());
        loan.setPaymentAccount(savings);
        when(settingsService.deliver(any(), any())).thenReturn(1);
        when(prefsLoader.load(userId)).thenReturn(new NotificationPrefs(settings, true, 9, List.of(7, 3, 1, 0), kinds));
    }

    @AfterEach
    void tearDown() {
        AppTime.reset();
    }

    private void scheduleWith(List<LoanPayment> payments) {
        ScheduleResult schedule = new LoanScheduleService().compute(loan, List.of(), payments, List.of(), TODAY);
        when(loanService.getLoansWithSchedule(any())).thenReturn(List.of(new LoanService.LoanWithSchedule(loan, schedule)));
    }

    @Test
    void anUnsettledOverdueInstallmentIsRemindedAbout() {
        scheduleWith(List.of());

        assertEquals(new NotificationOutcome(1, 1, 1), service.evaluate(userId));
        assertEquals("OVERDUE", loan.getLastNotifiedKind());
    }

    @Test
    void anOverdueInstallmentSettledByAnAutoLinkedDebitIsNotReminded() {
        LoanPayment auto = new LoanPayment();
        auto.setLoan(loan);
        auto.setInstallmentSeq(1);
        auto.setPaymentDate(TODAY.minusDays(2));
        auto.setAmount(new BigDecimal("10661.85"));
        auto.setOrigin(LinkOrigin.AUTO);
        scheduleWith(List.of(auto));

        assertEquals(new NotificationOutcome(1, 0, 0), service.evaluate(userId));
        verify(settingsService, never()).deliver(any(), any());
        assertTrue(loan.getLastNotifiedKind() == null || !"OVERDUE".equals(loan.getLastNotifiedKind()));
    }
}
