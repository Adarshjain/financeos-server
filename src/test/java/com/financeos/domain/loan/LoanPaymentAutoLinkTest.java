package com.financeos.domain.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.LoanPaymentResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.AutoLinkStore;
import com.financeos.domain.transaction.autolink.SuggestionKind;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The AUTO origin on loan payments and the rejection recorded when one is deleted. */
class LoanPaymentAutoLinkTest {

    private final LoanRepository loanRepository = mock(LoanRepository.class);
    private final LoanPaymentRepository paymentRepository = mock(LoanPaymentRepository.class);
    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final TransactionReferenceValidator validator = mock(TransactionReferenceValidator.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AutoLinkStore autoLinkStore = mock(AutoLinkStore.class);
    private final LoanService service = new LoanService(loanRepository, mock(LoanEventRepository.class), paymentRepository,
            mock(LoanChargeRepository.class), mock(AccountRepository.class), transactionRepository, userRepository,
            new LoanScheduleService(), validator);

    private final UUID userId = UUID.randomUUID();
    private User user;
    private Loan loan;

    @BeforeEach
    void setUp() {
        service.setAutoLinkStore(autoLinkStore);
        user = new User();
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
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(paymentRepository.save(any(LoanPayment.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private LoanPayment payment(LinkOrigin origin, Transaction transaction) {
        LoanPayment p = new LoanPayment();
        p.setId(UUID.randomUUID());
        p.setLoan(loan);
        p.setUser(user);
        p.setInstallmentSeq(2);
        p.setAmount(new BigDecimal("10000"));
        p.setPaymentDate(LocalDate.of(2026, 3, 5));
        p.setTransaction(transaction);
        p.setOrigin(origin);
        when(paymentRepository.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private Transaction debit() {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setType(TransactionType.DEBIT);
        t.setAmount(new BigDecimal("10661.85"));
        t.setDate(LocalDate.of(2026, 3, 6));
        return t;
    }

    @Test
    void deletingAnAutoPaymentRemembersTheTransactionInstallmentPair() {
        Transaction t = debit();
        LoanPayment p = payment(LinkOrigin.AUTO, t);

        service.deletePayment(loan.getId(), p.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<AutoLinkStore.Rejection>> captor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(autoLinkStore).addRejections(org.mockito.ArgumentMatchers.eq(userId), captor.capture());
        assertThat(captor.getValue()).containsExactly(new AutoLinkStore.Rejection(SuggestionKind.EMI, t.getId(),
                LoanService.installmentRef(loan.getId(), 2)));
        verify(paymentRepository).delete(p);
    }

    @Test
    void deletingAUserPaymentRecordsNothing() {
        LoanPayment p = payment(LinkOrigin.USER, debit());

        service.deletePayment(loan.getId(), p.getId());

        verify(autoLinkStore, never()).addRejections(any(), anyCollection());
        verify(paymentRepository).delete(p);
    }

    @Test
    void deletingAnAutoPaymentWithoutATransactionRecordsNothing() {
        LoanPayment p = payment(LinkOrigin.AUTO, null);

        service.deletePayment(loan.getId(), p.getId());

        verify(autoLinkStore, never()).addRejections(any(), anyCollection());
    }

    @Test
    void addAutoPaymentSavesAnAutoPaymentWithTheTransactionAmountAndDate() {
        Transaction t = debit();
        when(transactionRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(validator.validateForLoan(t.getId())).thenReturn(t);

        LoanPaymentResponse response = service.addAutoPayment(loan.getId(), 1, t.getId());

        ArgumentCaptor<LoanPayment> saved = ArgumentCaptor.forClass(LoanPayment.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getOrigin()).isEqualTo(LinkOrigin.AUTO);
        assertThat(saved.getValue().getInstallmentSeq()).isEqualTo(1);
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("10661.85");
        assertThat(saved.getValue().getPaymentDate()).isEqualTo(LocalDate.of(2026, 3, 6));
        assertThat(saved.getValue().getTransaction()).isSameAs(t);
        assertThat(response.origin()).isEqualTo(LinkOrigin.AUTO);
    }

    @Test
    void aManualPaymentIsAUserPayment() {
        Transaction t = debit();
        when(validator.validateForLoan(t.getId())).thenReturn(t);

        LoanPaymentResponse response = service.addPayment(loan.getId(),
                new com.financeos.api.loan.dto.CreateLoanPaymentRequest(t.getDate(), t.getAmount(), 1, t.getId()));

        assertThat(response.origin()).isEqualTo(LinkOrigin.USER);
    }

    @Test
    void unsettledInstallmentsCoverTheWholeScheduleMinusTheSettledOnes() {
        LoanPayment settled = payment(LinkOrigin.USER, null);
        settled.setInstallmentSeq(1);
        when(loanRepository.findAll()).thenReturn(List.of(loan));
        when(paymentRepository.findByLoan_IdInOrderByInstallmentSeqAsc(any())).thenReturn(List.of(settled));

        List<LoanService.UnsettledInstallment> out = service.getUnsettledInstallments();

        assertThat(out).hasSize(11);
        assertThat(out).extracting(LoanService.UnsettledInstallment::seq).doesNotContain(1);
        assertThat(out).allMatch(i -> i.loanId().equals(loan.getId()));
    }

    @Test
    void aLoanWhoseScheduleCannotBeComputedIsSkippedAndTheOthersStillCount() {
        Loan bad = new Loan();
        bad.setId(UUID.randomUUID());
        bad.setUser(user);
        bad.setStatus(LoanStatus.active); // no principal, rate or tenure: compute throws
        when(loanRepository.findAll()).thenReturn(List.of(bad, loan));

        List<LoanService.UnsettledInstallment> out = service.getUnsettledInstallments();

        assertThat(out).hasSize(12);
        assertThat(out).allMatch(i -> i.loanId().equals(loan.getId()));
    }

    @Test
    void closedAndForeclosedLoansNeverFeedTheAutoLinker() {
        Loan closed = new Loan();
        closed.setId(UUID.randomUUID());
        closed.setUser(user);
        closed.setStatus(LoanStatus.closed);
        Loan foreclosed = new Loan();
        foreclosed.setId(UUID.randomUUID());
        foreclosed.setUser(user);
        foreclosed.setStatus(LoanStatus.foreclosed);
        when(loanRepository.findAll()).thenReturn(List.of(closed, foreclosed, loan));

        assertThat(service.getUnsettledInstallments()).allMatch(i -> i.loanId().equals(loan.getId()));
    }
}
