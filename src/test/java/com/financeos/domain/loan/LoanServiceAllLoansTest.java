package com.financeos.domain.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.InstallmentDto;
import com.financeos.api.loan.dto.LoanMatchSuggestionsResponse;
import com.financeos.api.loan.dto.MatchSuggestionsResponse;
import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.loan.LoanService.LoanTotals;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Loan totals, search routing and all-loans match suggestions in {@link LoanService}. */
class LoanServiceAllLoansTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);
    private static final UUID USER_ID = UUID.randomUUID();

    private LoanRepository loanRepository;
    private LoanEventRepository loanEventRepository;
    private LoanPaymentRepository loanPaymentRepository;
    private LoanChargeRepository loanChargeRepository;
    private TransactionRepository transactionRepository;
    private TransactionReferenceValidator validator;
    private LoanService loanService;

    @BeforeEach
    void setUp() {
        AppTime.useClock(Clock.fixed(TODAY.atTime(10, 0).atZone(IST).toInstant(), IST));
        loanRepository = mock(LoanRepository.class);
        loanEventRepository = mock(LoanEventRepository.class);
        loanPaymentRepository = mock(LoanPaymentRepository.class);
        loanChargeRepository = mock(LoanChargeRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        validator = mock(TransactionReferenceValidator.class);
        when(validator.getAllReferencedTransactionIds()).thenReturn(Set.of());
        loanService = new LoanService(loanRepository, loanEventRepository, loanPaymentRepository, loanChargeRepository,
                mock(AccountRepository.class), transactionRepository, mock(UserRepository.class),
                new LoanScheduleService(), validator);
        UserContext.setCurrentUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AppTime.reset();
    }

    private Loan loan(String name, LoanStatus status, String principal, LocalDate firstEmi, int tenure) {
        User owner = new User();
        owner.setId(USER_ID);
        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setUser(owner);
        loan.setName(name);
        loan.setLender("Bank");
        loan.setLoanType(LoanType.personal);
        loan.setPrincipal(new BigDecimal(principal));
        loan.setAnnualRatePct(new BigDecimal("12"));
        loan.setRateType(RateType.fixed);
        loan.setTenureMonths(tenure);
        loan.setStartDate(firstEmi.minusMonths(1));
        loan.setFirstEmiDate(firstEmi);
        loan.setEmiAmount(new LoanScheduleService().calculateAnnuityEmi(loan.getPrincipal(), loan.getAnnualRatePct(), tenure));
        loan.setStatus(status);
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(loan.getId())).thenReturn(List.of());
        when(loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(loan.getId())).thenReturn(List.of());
        when(loanChargeRepository.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(loan.getId())).thenReturn(List.of());
        return loan;
    }

    private LoanPayment paymentFor(Loan loan, int seq) {
        LoanPayment p = new LoanPayment();
        p.setId(UUID.randomUUID());
        p.setLoan(loan);
        p.setInstallmentSeq(seq);
        p.setPaymentDate(TODAY);
        p.setAmount(loan.getEmiAmount());
        return p;
    }

    @Test
    void totalsCoverActiveLoansAndCountClosedOnes() {
        // A: EMIs on the 5th from Jan 2026; installments 1-2 paid, 3 (2026-03-05) overdue.
        Loan a = loan("A", LoanStatus.active, "120000", LocalDate.of(2026, 1, 5), 12);
        // B: EMIs on the 10th, first one in the future.
        Loan b = loan("B", LoanStatus.active, "60000", LocalDate.of(2026, 4, 10), 6);
        Loan closed = loan("C", LoanStatus.closed, "1000", LocalDate.of(2025, 1, 1), 3);
        Loan fore = loan("D", LoanStatus.foreclosed, "1000", LocalDate.of(2025, 1, 1), 3);
        when(loanRepository.findAll()).thenReturn(List.of(a, b, closed, fore));
        when(loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(a.getId()))
                .thenReturn(List.of(paymentFor(a, 1), paymentFor(a, 2)));

        LoanTotals t = loanService.getLoanTotals();

        assertEquals(0, new BigDecimal("180000").compareTo(t.borrowedPrincipal()));
        BigDecimal emiSum = a.getEmiAmount().add(b.getEmiAmount());
        assertTrue(emiSum.subtract(t.monthlyEmiTotal()).abs().compareTo(new BigDecimal("0.05")) <= 0);
        assertEquals(List.of(5, 10), t.emiDueDays());
        assertEquals(1, t.overdueCount());
        assertTrue(a.getEmiAmount().subtract(t.overdueAmount()).abs().compareTo(new BigDecimal("0.05")) <= 0);
        assertEquals(LocalDate.of(2025, 4, 1), t.fyStart());
        assertEquals(2, t.closedLoanCount());
    }

    @Test
    void interestPaidThisFyCountsSettledInstallmentsDueInTheFinancialYear() {
        Loan a = loan("A", LoanStatus.active, "120000", LocalDate.of(2025, 12, 20), 12);
        List<LoanPayment> paid = List.of(paymentFor(a, 1), paymentFor(a, 2));
        when(loanRepository.findAll()).thenReturn(List.of(a));
        when(loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(a.getId())).thenReturn(paid);

        LoanTotals t = loanService.getLoanTotals();

        BigDecimal expected = new LoanScheduleService().compute(a, List.of(), paid, List.of()).installments().stream()
                .filter(i -> "settled".equals(i.status()))
                .map(InstallmentDto::interest)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, expected.compareTo(t.interestPaidThisFy()));
        assertTrue(t.interestPaidThisFy().signum() > 0);
    }

    @Test
    void interestPaidInAnEarlierFinancialYearIsExcluded() {
        Loan a = loan("A", LoanStatus.active, "120000", LocalDate.of(2025, 1, 5), 3);
        when(loanRepository.findAll()).thenReturn(List.of(a));
        when(loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(a.getId())).thenReturn(List.of(paymentFor(a, 1)));

        assertEquals(0, BigDecimal.ZERO.compareTo(loanService.getLoanTotals().interestPaidThisFy()));
    }

    @Test
    void blankSearchUsesTheStatusOnlyQuery() {
        when(loanRepository.findByStatus(eq(LoanStatus.active), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        loanService.getLoans(LoanStatus.active, "  ", PageRequest.of(0, 10));

        verify(loanRepository).findByStatus(eq(LoanStatus.active), any(Pageable.class));
    }

    @Test
    void searchPassesALoweredEscapedContainsPatternWithTheStatus() {
        when(loanRepository.searchByStatus(any(), any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(loanRepository.search(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        loanService.getLoans(LoanStatus.active, " HDFC_1% ", PageRequest.of(0, 10));
        loanService.getLoans(null, "Car", PageRequest.of(0, 10));

        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(loanRepository).searchByStatus(eq(LoanStatus.active), pattern.capture(), any(Pageable.class));
        assertEquals("%hdfc\\_1\\%%", pattern.getValue());
        verify(loanRepository).search(eq("%car%"), any(Pageable.class));
    }

    private Transaction debit(String amount) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        com.financeos.domain.account.Account account = new com.financeos.domain.account.Account();
        account.setId(UUID.randomUUID());
        t.setAccount(account);
        t.setAmount(new BigDecimal(amount));
        t.setDate(TODAY);
        t.setType(TransactionType.DEBIT);
        t.setDescription("EMI");
        return t;
    }

    /** Stubs the candidate query to filter the pool by the amount range the service asks for. */
    private void stubCandidates(List<Transaction> pool) {
        when(transactionRepository.findMatchCandidates(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            BigDecimal min = inv.getArgument(1);
            BigDecimal max = inv.getArgument(2);
            List<Transaction> out = new ArrayList<>();
            for (Transaction t : pool) {
                if (t.getAmount().compareTo(min) >= 0 && t.getAmount().compareTo(max) <= 0) {
                    out.add(t);
                }
            }
            return out;
        });
    }

    private Loan fiveThousandEmiLoan(String name) {
        Loan loan = loan(name, LoanStatus.active, "100000", TODAY, 24);
        loan.setEmiAmount(new BigDecimal("5000"));
        return loan;
    }

    @Test
    void suggestionsIncludeADebitNinetyOffAndExcludeOneHundredFiftyOff() {
        Loan a = fiveThousandEmiLoan("A");
        Transaction near = debit("4910");
        Transaction far = debit("4850");
        stubCandidates(List.of(near, far));

        MatchSuggestionsResponse r = loanService.getMatchSuggestions(a.getId());

        List<UUID> ids = r.suggestions().stream().flatMap(s -> s.candidates().stream()).map(c -> c.id()).toList();
        assertTrue(ids.contains(near.getId()));
        assertFalse(ids.contains(far.getId()));
    }

    @Test
    void allLoansSuggestionsOmitLoansWithoutCandidates() {
        Loan withMatch = fiveThousandEmiLoan("With");
        Loan without = loan("Without", LoanStatus.active, "100000", TODAY.plusMonths(3), 24);
        when(loanRepository.findByStatus(LoanStatus.active)).thenReturn(List.of(withMatch, without));
        stubCandidates(List.of(debit("4950")));

        List<LoanMatchSuggestionsResponse> result = loanService.getAllMatchSuggestions();

        assertEquals(1, result.size());
        assertEquals(withMatch.getId(), result.get(0).loanId());
        assertEquals("With", result.get(0).loanName());
        assertTrue(result.get(0).suggestions().stream().anyMatch(s -> !s.candidates().isEmpty()));
    }

    @Test
    void allLoansSuggestionsAreEmptyWhenNothingMatches() {
        Loan a = fiveThousandEmiLoan("A");
        when(loanRepository.findByStatus(LoanStatus.active)).thenReturn(List.of(a));
        stubCandidates(List.of());

        assertTrue(loanService.getAllMatchSuggestions().isEmpty());
    }

    @Test
    void aLoanWhoseScheduleCannotBeComputedIsSkippedFromTotals() {
        Loan good = loan("Good", LoanStatus.active, "60000", LocalDate.of(2026, 4, 10), 6);
        Loan bad = loan("Bad", LoanStatus.closed, "1000", LocalDate.of(2025, 1, 1), 3);
        when(loanRepository.findAll()).thenReturn(List.of(bad, good));
        when(loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(bad.getId()))
                .thenThrow(new IllegalStateException("bad event"));

        LoanTotals t = loanService.getLoanTotals();

        assertEquals(0, new BigDecimal("60000").compareTo(t.borrowedPrincipal()));
        assertEquals(1, t.activeLoanCount());
        assertEquals(1, t.closedLoanCount());
    }

    @Test
    void aFinishedActiveLoanAddsNoEmiAndNoDueDay() {
        Loan done = loan("Done", LoanStatus.active, "30000", LocalDate.of(2025, 1, 5), 3);
        List<LoanPayment> all = List.of(paymentFor(done, 1), paymentFor(done, 2), paymentFor(done, 3));
        when(loanRepository.findAll()).thenReturn(List.of(done));
        when(loanPaymentRepository.findByLoan_IdOrderByInstallmentSeqAsc(done.getId())).thenReturn(all);

        LoanTotals t = loanService.getLoanTotals();

        assertTrue(t.emiDueDays().isEmpty());
        assertEquals(0, BigDecimal.ZERO.compareTo(t.monthlyEmiTotal()));
        assertEquals(0, t.overdueCount());
    }

    @Test
    void allLoansSuggestionsSkipALoanThatThrows() {
        Loan bad = fiveThousandEmiLoan("Bad");
        Loan good = fiveThousandEmiLoan("Good");
        when(loanRepository.findByStatus(LoanStatus.active)).thenReturn(List.of(bad, good));
        when(loanEventRepository.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(bad.getId()))
                .thenThrow(new IllegalStateException("boom"));
        stubCandidates(List.of(debit("4950")));

        List<LoanMatchSuggestionsResponse> result = loanService.getAllMatchSuggestions();

        assertEquals(1, result.size());
        assertEquals("Good", result.get(0).loanName());
    }

    @Test
    void twentyThousandEmiMatchesOneNinetyOffButNotTwoFiftyOff() {
        // Tolerance is 1% = 200 for a 20,000 EMI.
        Loan a = loan("Big", LoanStatus.active, "400000", TODAY, 24);
        a.setEmiAmount(new BigDecimal("20000"));
        Transaction near = debit("19810");
        Transaction far = debit("19750");
        stubCandidates(List.of(near, far));

        List<UUID> ids = loanService.getMatchSuggestions(a.getId()).suggestions().stream()
                .flatMap(s -> s.candidates().stream()).map(c -> c.id()).toList();

        assertTrue(ids.contains(near.getId()));
        assertFalse(ids.contains(far.getId()));
    }
}
