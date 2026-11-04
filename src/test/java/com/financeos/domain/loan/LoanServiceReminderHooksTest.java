package com.financeos.domain.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.BatchLoanPaymentItem;
import com.financeos.api.loan.dto.BatchLoanPaymentRequest;
import com.financeos.api.loan.dto.CreateLoanPaymentRequest;
import com.financeos.api.loan.dto.LoanDetailResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.notification.reminder.ReminderKeys;
import com.financeos.domain.notification.reminder.ReminderResolveEvent;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/** The EMI reminder hooks on LoanService: payments resolve the feed row, the "Is this loan closed?" prompt. */
class LoanServiceReminderHooksTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private final LoanRepository loanRepository = mock(LoanRepository.class);
    private final LoanPaymentRepository paymentRepository = mock(LoanPaymentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final LoanService service = new LoanService(loanRepository, mock(LoanEventRepository.class), paymentRepository,
            mock(LoanChargeRepository.class), mock(AccountRepository.class), mock(TransactionRepository.class), userRepository,
            new LoanScheduleService(), mock(TransactionReferenceValidator.class));

    private final UUID userId = UUID.randomUUID();
    private Loan loan;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        service.setEventPublisher(publisher);
        User user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        loan = loanOf(user);
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(paymentRepository.save(any(LoanPayment.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private static Loan loanOf(User user) {
        Loan l = new Loan();
        l.setId(UUID.randomUUID());
        l.setUser(user);
        l.setName("Home");
        l.setLoanType(LoanType.home);
        l.setPrincipal(new BigDecimal("120000"));
        l.setAnnualRatePct(new BigDecimal("12"));
        l.setRateType(RateType.fixed);
        l.setTenureMonths(12);
        l.setStartDate(LocalDate.of(2026, 1, 1));
        l.setFirstEmiDate(LocalDate.of(2026, 2, 5));
        l.setStatus(LoanStatus.active);
        l.setNotificationsMuted(false);
        return l;
    }

    // ---------------------------------------------------------------- feed row resolves when the payment is recorded

    @Test
    void recordingAPaymentResolvesThatInstallmentsFeedRow() {
        service.addPayment(loan.getId(), new CreateLoanPaymentRequest(LocalDate.of(2026, 2, 5), new BigDecimal("10661"), 1, null));

        ArgumentCaptor<ReminderResolveEvent> event = ArgumentCaptor.forClass(ReminderResolveEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isEqualTo(new ReminderResolveEvent(userId, ReminderKeys.emi(loan.getId(), 1)));
    }

    @Test
    void aRejectedPaymentResolvesNothing() {
        assertThrows(ValidationException.class,
                () -> service.addPayment(loan.getId(), new CreateLoanPaymentRequest(LocalDate.of(2026, 2, 5), BigDecimal.ZERO, 1, null)));

        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aBatchResolvesEveryInstallmentItRecorded() {
        service.addPaymentsBatch(loan.getId(), new BatchLoanPaymentRequest(List.of(
                new BatchLoanPaymentItem(1, LocalDate.of(2026, 2, 5), new BigDecimal("10661"), null),
                new BatchLoanPaymentItem(2, LocalDate.of(2026, 3, 5), new BigDecimal("10661"), null))));

        ArgumentCaptor<ReminderResolveEvent> event = ArgumentCaptor.forClass(ReminderResolveEvent.class);
        verify(publisher, org.mockito.Mockito.times(2)).publishEvent(event.capture());
        assertThat(event.getAllValues()).extracting(ReminderResolveEvent::dedupeKey)
                .containsExactlyInAnyOrder(ReminderKeys.emi(loan.getId(), 1), ReminderKeys.emi(loan.getId(), 2));
        assertThat(event.getAllValues()).allMatch(e -> e.userId().equals(userId));
    }

    // ---------------------------------------------------------------- Is this loan closed?

    @Test
    void theLoanDetailAsksWhenTheOldestUnpaidInstallmentIs45DaysOverdue() {
        LoanDetailResponse detail = service.getLoanDetail(loan.getId());

        assertThat(detail.askClosed()).isTrue();
    }

    @Test
    void theLoanDetailDoesNotAskForAFreshLoan() {
        loan.setFirstEmiDate(TODAY.plusDays(10));

        assertThat(service.getLoanDetail(loan.getId()).askClosed()).isFalse();
    }

    @Test
    void theLoanDetailDoesNotAskForAClosedLoan() {
        loan.setStatus(LoanStatus.closed);

        assertThat(service.getLoanDetail(loan.getId()).askClosed()).isFalse();
    }

    @Test
    void dismissingRestsThePromptAndStampsTheLoan() {
        service.dismissClosedPrompt(loan.getId());

        assertThat(loan.getClosedPromptDismissedAt()).isNotNull();
        verify(loanRepository).save(loan);
        assertThat(service.getLoanDetail(loan.getId()).askClosed()).isFalse();
    }

    @Test
    void anOldDismissalAsksAgain() {
        loan.setClosedPromptDismissedAt(TODAY.minusDays(46).atTime(12, 0).atZone(IST).toInstant());

        assertThat(service.getLoanDetail(loan.getId()).askClosed()).isTrue();
    }

    @Test
    void anotherUsersLoanCannotBeDismissed() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        Loan foreign = loanOf(stranger);
        when(loanRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThrows(RuntimeException.class, () -> service.dismissClosedPrompt(foreign.getId()));

        assertThat(foreign.getClosedPromptDismissedAt()).isNull();
        verify(loanRepository, never()).save(foreign);
    }

    @Test
    void dismissalsListsOnlyDismissedLoans() {
        Loan dismissed = loanOf(loan.getUser());
        dismissed.setClosedPromptDismissedAt(Instant.parse("2026-10-30T00:00:00Z"));
        when(loanRepository.findAll()).thenReturn(List.of(loan, dismissed));

        assertThat(service.closedPromptDismissals()).containsOnlyKeys(dismissed.getId());
    }

    @Test
    void closingTheLoanResolvesAllItsEmiRows() {
        service.closeLoan(loan.getId());

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.closed);
        verify(publisher).publishEvent(ReminderResolveEvent.allEmisOf(userId, loan.getId()));
        assertThat(ReminderResolveEvent.allEmisOf(userId, loan.getId()).dedupeKey()).isEqualTo("emi:" + loan.getId() + ":");
    }

    @Test
    void deletingTheLoanResolvesAllItsEmiRows() {
        service.deleteLoan(loan.getId());

        verify(loanRepository).delete(loan);
        verify(publisher).publishEvent(ReminderResolveEvent.allEmisOf(userId, loan.getId()));
    }
}
