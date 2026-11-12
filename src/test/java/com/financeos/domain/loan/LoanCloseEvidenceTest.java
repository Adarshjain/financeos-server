package com.financeos.domain.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.LoanCloseEvidence;
import com.financeos.api.loan.dto.LoanDetailResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.schedule.LoanScheduleService;
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

/** The loan detail carries the facts behind "Is this loan closed?" only while the prompt is asked. */
class LoanCloseEvidenceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private final LoanRepository loanRepository = mock(LoanRepository.class);
    private final LoanPaymentRepository paymentRepository = mock(LoanPaymentRepository.class);
    private final LoanService service = new LoanService(loanRepository, mock(LoanEventRepository.class), paymentRepository,
            mock(LoanChargeRepository.class), mock(AccountRepository.class), mock(TransactionRepository.class),
            mock(UserRepository.class), new LoanScheduleService(), mock(TransactionReferenceValidator.class));

    private final UUID userId = UUID.randomUUID();
    private Loan loan;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        User user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setUser(user);
        loan.setName("Home");
        loan.setLoanType(LoanType.home);
        loan.setPrincipal(new BigDecimal("120000"));
        loan.setAnnualRatePct(new BigDecimal("12"));
        loan.setRateType(RateType.fixed);
        loan.setTenureMonths(12);
        loan.setStartDate(LocalDate.of(2026, 1, 1));
        loan.setFirstEmiDate(LocalDate.of(2026, 2, 5));
        loan.setStatus(LoanStatus.active);
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private LoanPayment payment(int seq, LocalDate date, String amount) {
        LoanPayment p = new LoanPayment();
        p.setId(UUID.randomUUID());
        p.setLoan(loan);
        p.setInstallmentSeq(seq);
        p.setPaymentDate(date);
        p.setAmount(new BigDecimal(amount));
        return p;
    }

    @Test
    void evidenceNamesTheOldestUnpaidInstallmentAndTheLastPayment() {
        when(paymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loan.getId())).thenReturn(List.of(
                payment(1, LocalDate.of(2026, 2, 5), "10661"), payment(2, LocalDate.of(2026, 3, 7), "10661")));

        LoanDetailResponse detail = service.getLoanDetail(loan.getId());

        assertThat(detail.askClosed()).isTrue();
        LoanCloseEvidence e = detail.closeEvidence();
        assertThat(e.oldestUnpaidSeq()).isEqualTo(3);
        assertThat(e.oldestUnpaidDueDate()).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(e.daysOverdue()).isEqualTo(213);
        assertThat(e.lastPaymentDate()).isEqualTo(LocalDate.of(2026, 3, 7));
        assertThat(e.lastPaymentAmount()).isEqualByComparingTo("10661");
        assertThat(e.settledInstallments()).isEqualTo(2);
        assertThat(e.totalInstallments()).isEqualTo(12);
        assertThat(e.outstandingPrincipal()).isEqualByComparingTo(detail.loan().outstandingPrincipal());
    }

    @Test
    void aLoanWithNoPaymentsHasNullLastPayment() {
        LoanCloseEvidence e = service.getLoanDetail(loan.getId()).closeEvidence();

        assertThat(e.oldestUnpaidSeq()).isEqualTo(1);
        assertThat(e.lastPaymentDate()).isNull();
        assertThat(e.lastPaymentAmount()).isNull();
        assertThat(e.settledInstallments()).isZero();
    }

    @Test
    void noEvidenceWhenThePromptIsNotAsked() {
        loan.setFirstEmiDate(TODAY.plusDays(10));
        assertThat(service.getLoanDetail(loan.getId()).closeEvidence()).isNull();

        loan.setFirstEmiDate(LocalDate.of(2026, 2, 5));
        loan.setClosedPromptDismissedAt(TODAY.atTime(9, 0).atZone(IST).toInstant());
        LoanDetailResponse dismissed = service.getLoanDetail(loan.getId());
        assertThat(dismissed.askClosed()).isFalse();
        assertThat(dismissed.closeEvidence()).isNull();
    }

    @Test
    void anotherUsersLoanIsNotVisible() {
        User stranger = new User();
        stranger.setId(UUID.randomUUID());
        loan.setUser(stranger);

        assertThatThrownBy(() -> service.getLoanDetail(loan.getId())).isInstanceOf(RuntimeException.class);
    }
}
