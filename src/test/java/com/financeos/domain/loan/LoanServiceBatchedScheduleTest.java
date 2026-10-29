package com.financeos.domain.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.loan.schedule.ScheduleResult;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** The batched schedule loading in {@link LoanService} gives each loan the schedule the per-loan reads gave it, in three queries total. */
class LoanServiceBatchedScheduleTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    private final LoanScheduleService scheduleService = new LoanScheduleService();
    private LoanRepository loanRepository;
    private LoanEventRepository eventRepository;
    private LoanPaymentRepository paymentRepository;
    private LoanChargeRepository chargeRepository;
    private LoanService service;
    private final List<Loan> loans = new ArrayList<>();
    private final List<LoanPayment> payments = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        UserContext.setCurrentUserId(UUID.randomUUID());
        loanRepository = mock(LoanRepository.class);
        eventRepository = mock(LoanEventRepository.class);
        paymentRepository = mock(LoanPaymentRepository.class);
        chargeRepository = mock(LoanChargeRepository.class);
        TransactionReferenceValidator validator = mock(TransactionReferenceValidator.class);
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of());
        service = new LoanService(loanRepository, eventRepository, paymentRepository, chargeRepository,
                mock(AccountRepository.class), mock(com.financeos.domain.transaction.TransactionRepository.class),
                mock(UserRepository.class), scheduleService, validator);
        when(paymentRepository.findByLoan_IdInOrderByInstallmentSeqAsc(any())).thenAnswer(inv -> {
            java.util.Collection<UUID> ids = inv.getArgument(0);
            return payments.stream().filter(p -> ids.contains(p.getLoan().getId())).toList();
        });
        when(loanRepository.findAll()).thenReturn(loans);
        // Three loans with different shapes and different numbers of recorded payments.
        for (int n = 0; n < 3; n++) {
            Loan loan = new Loan();
            loan.setId(UUID.randomUUID());
            loan.setName("Loan " + n);
            loan.setLender("Bank");
            loan.setLoanType(LoanType.personal);
            loan.setPrincipal(new BigDecimal(100000 * (n + 1)));
            loan.setAnnualRatePct(new BigDecimal("10").add(BigDecimal.valueOf(n)));
            loan.setTenureMonths(6 + 6 * n);
            loan.setStartDate(LocalDate.of(2025, 12, 1));
            loan.setFirstEmiDate(LocalDate.of(2026, 1, 5 + n));
            loan.setEmiAmount(scheduleService.calculateAnnuityEmi(loan.getPrincipal(), loan.getAnnualRatePct(), loan.getTenureMonths()));
            loan.setStatus(n == 2 ? LoanStatus.closed : LoanStatus.active);
            loans.add(loan);
            for (int seq = 1; seq <= n + 1; seq++) {
                LoanPayment p = new LoanPayment();
                p.setId(UUID.randomUUID());
                p.setLoan(loan);
                p.setInstallmentSeq(seq);
                p.setPaymentDate(TODAY);
                p.setAmount(loan.getEmiAmount());
                payments.add(p);
            }
        }
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private ScheduleResult perLoan(Loan loan) {
        return scheduleService.compute(loan, List.of(),
                payments.stream().filter(p -> p.getLoan() == loan).toList(), List.of());
    }

    @Test
    void allLoansWithScheduleMatchesPerLoanComputationInThreeQueries() {
        List<LoanService.LoanWithSchedule> result = service.getAllLoansWithSchedule();

        assertEquals(3, result.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(loans.get(i), result.get(i).loan());
            assertEquals(perLoan(loans.get(i)), result.get(i).schedule());
        }
        verify(eventRepository, times(1)).findByLoan_IdInOrderByEffectiveDateAscCreatedAtAsc(any());
        verify(paymentRepository, times(1)).findByLoan_IdInOrderByInstallmentSeqAsc(any());
        verify(chargeRepository, times(1)).findByLoan_IdInOrderByChargeDateAscCreatedAtAsc(any());
        verify(eventRepository, never()).findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(any());
        verify(paymentRepository, never()).findByLoan_IdOrderByInstallmentSeqAsc(any());
        verify(chargeRepository, never()).findByLoan_IdOrderByChargeDateAscCreatedAtAsc(any());
    }

    @Test
    void theLoanListPageGetsTheSameSchedulesInThreeQueries() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(loanRepository.findAll(pageable)).thenReturn(new PageImpl<>(loans, pageable, loans.size()));

        var page = service.getLoans(null, pageable);

        assertEquals(3, page.getContent().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(com.financeos.api.loan.dto.LoanResponse.from(loans.get(i), perLoan(loans.get(i))), page.getContent().get(i));
        }
        verify(paymentRepository, times(1)).findByLoan_IdInOrderByInstallmentSeqAsc(any());
        verify(paymentRepository, never()).findByLoan_IdOrderByInstallmentSeqAsc(any());
    }

    @Test
    void anEmptyLoanListRunsNoScheduleQueries() {
        loans.clear();

        assertEquals(List.of(), service.getAllLoansWithSchedule());
        verify(paymentRepository, never()).findByLoan_IdInOrderByInstallmentSeqAsc(any());
    }

    @Test
    void filteredScheduleLoadsInputsOnlyForMatchingLoansAndMatchesTheUnfilteredSchedules() {
        List<LoanService.LoanWithSchedule> all = service.getAllLoansWithSchedule();
        org.mockito.Mockito.clearInvocations(eventRepository, paymentRepository, chargeRepository);

        List<LoanService.LoanWithSchedule> active = service.getLoansWithSchedule(l -> l.getStatus() == LoanStatus.active);

        assertEquals(all.subList(0, 2), active);
        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> ids = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(paymentRepository, times(1)).findByLoan_IdInOrderByInstallmentSeqAsc(ids.capture());
        assertEquals(List.of(loans.get(0).getId(), loans.get(1).getId()), List.copyOf(ids.getValue()));
        verify(eventRepository, times(1)).findByLoan_IdInOrderByEffectiveDateAscCreatedAtAsc(ids.capture());
        assertEquals(2, ids.getValue().size());
    }
}
