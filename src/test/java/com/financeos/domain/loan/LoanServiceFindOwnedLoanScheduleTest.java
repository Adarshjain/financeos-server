package com.financeos.domain.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.LoanEventResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.LoanService.LoanScheduleDetail;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link LoanService#findOwnedLoanSchedule}: the user's loan with events and schedule, else empty. */
class LoanServiceFindOwnedLoanScheduleTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);
    private static final UUID USER_ID = UUID.randomUUID();

    private LoanRepository loanRepository;
    private LoanEventRepository loanEventRepository;
    private LoanPaymentRepository loanPaymentRepository;
    private LoanChargeRepository loanChargeRepository;
    private LoanScheduleService scheduleService;
    private LoanService loanService;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        loanRepository = mock(LoanRepository.class);
        loanEventRepository = mock(LoanEventRepository.class);
        loanPaymentRepository = mock(LoanPaymentRepository.class);
        loanChargeRepository = mock(LoanChargeRepository.class);
        scheduleService = new LoanScheduleService();
        loanService = new LoanService(loanRepository, loanEventRepository, loanPaymentRepository, loanChargeRepository,
                mock(AccountRepository.class), mock(TransactionRepository.class), mock(UserRepository.class),
                scheduleService, mock(TransactionReferenceValidator.class));
        UserContext.setCurrentUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    @Test
    void ownLoanComesWithItsEventsAndTheScheduleTheLoanListUses() {
        Loan loan = loan(USER_ID);
        LoanEvent prepayment = new LoanEvent();
        prepayment.setId(UUID.randomUUID());
        prepayment.setLoan(loan);
        prepayment.setEventType(LoanEventType.prepayment);
        prepayment.setEffectiveDate(LocalDate.of(2026, 1, 20));
        prepayment.setAmount(new BigDecimal("10000"));
        when(loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loan.getId())).thenReturn(List.of(prepayment));
        when(loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loan.getId())).thenReturn(List.of());
        when(loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loan.getId())).thenReturn(List.of());

        LoanScheduleDetail detail = loanService.findOwnedLoanSchedule(loan.getId()).orElseThrow();

        ScheduleResult expected = scheduleService.compute(loan, List.of(prepayment), List.of(), List.of());
        assertEquals(loan.getId(), detail.loan().id());
        assertEquals(expected.outstandingPrincipal(), detail.loan().outstandingPrincipal());
        assertEquals(expected.installments(), detail.installments());
        assertEquals(List.of(LoanEventResponse.from(prepayment)), detail.events());
    }

    @Test
    void someoneElsesLoanIsEmptyAndNeverScheduled() {
        Loan loan = loan(UUID.randomUUID());

        assertTrue(loanService.findOwnedLoanSchedule(loan.getId()).isEmpty());
        verifyNoInteractions(loanEventRepository, loanPaymentRepository, loanChargeRepository);
    }

    @Test
    void loanWithoutAnOwnerIsEmpty() {
        Loan loan = loan(null);
        loan.setUser(null);

        assertTrue(loanService.findOwnedLoanSchedule(loan.getId()).isEmpty());
    }

    @Test
    void missingLoanIsEmpty() {
        UUID id = UUID.randomUUID();
        when(loanRepository.findById(id)).thenReturn(Optional.empty());

        assertTrue(loanService.findOwnedLoanSchedule(id).isEmpty());
    }

    private Loan loan(UUID ownerId) {
        User owner = new User();
        owner.setId(ownerId);
        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setUser(owner);
        loan.setName("Car");
        loan.setLoanType(LoanType.home);
        loan.setPrincipal(new BigDecimal("100000"));
        loan.setAnnualRatePct(new BigDecimal("12"));
        loan.setRateType(RateType.fixed);
        loan.setTenureMonths(12);
        loan.setStartDate(LocalDate.of(2025, 12, 1));
        loan.setFirstEmiDate(LocalDate.of(2026, 1, 1));
        loan.setStatus(LoanStatus.active);
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        return loan;
    }
}
