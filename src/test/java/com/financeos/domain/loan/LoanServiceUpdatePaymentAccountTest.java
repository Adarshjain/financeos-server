package com.financeos.domain.loan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.loan.dto.LoanResponse;
import com.financeos.api.loan.dto.UpdateLoanRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.loan.schedule.LoanScheduleService;
import com.financeos.domain.transaction.TransactionRepository;
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

/** {@link LoanService#updateLoan}: keep, change, clear the payment account. */
class LoanServiceUpdatePaymentAccountTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private LoanRepository loanRepository;
    private AccountRepository accountRepository;
    private LoanService loanService;
    private Account oldAccount;

    @BeforeEach
    void setUp() {
        loanRepository = mock(LoanRepository.class);
        accountRepository = mock(AccountRepository.class);
        LoanEventRepository events = mock(LoanEventRepository.class);
        LoanPaymentRepository payments = mock(LoanPaymentRepository.class);
        LoanChargeRepository charges = mock(LoanChargeRepository.class);
        when(events.findByLoan_IdOrderByEffectiveDateAscCreatedAtAsc(any())).thenReturn(List.of());
        when(payments.findByLoan_IdOrderByInstallmentSeqAsc(any())).thenReturn(List.of());
        when(charges.findByLoan_IdOrderByChargeDateAscCreatedAtAsc(any())).thenReturn(List.of());
        when(loanRepository.save(any(Loan.class))).thenAnswer(i -> i.getArgument(0));
        loanService = new LoanService(loanRepository, events, payments, charges, accountRepository,
                mock(TransactionRepository.class), mock(UserRepository.class), new LoanScheduleService(),
                mock(TransactionReferenceValidator.class));
        UserContext.setCurrentUserId(USER_ID);
        oldAccount = bank(USER_ID);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void keptWhenNeitherFieldSent() {
        Loan loan = loan(oldAccount);
        loanService.updateLoan(loan.getId(), req(null, null));
        assertEquals(oldAccount, loan.getPaymentAccount());
    }

    @Test
    void keptWhenClearIsFalse() {
        Loan loan = loan(oldAccount);
        loanService.updateLoan(loan.getId(), req(null, false));
        assertEquals(oldAccount, loan.getPaymentAccount());
    }

    @Test
    void changedWhenNewIdSent() {
        Loan loan = loan(oldAccount);
        Account other = bank(USER_ID);
        when(accountRepository.findById(other.getId())).thenReturn(Optional.of(other));
        LoanResponse res = loanService.updateLoan(loan.getId(), req(other.getId(), null));
        assertEquals(other, loan.getPaymentAccount());
        assertEquals(other.getId(), res.paymentAccountId());
    }

    @Test
    void clearedWhenClearTrue() {
        Loan loan = loan(oldAccount);
        LoanResponse res = loanService.updateLoan(loan.getId(), req(null, true));
        assertNull(loan.getPaymentAccount());
        assertNull(res.paymentAccountId());
        verify(loanRepository).save(loan);
    }

    @Test
    void bothFieldsSentIsRejectedAndNothingChanges() {
        Loan loan = loan(oldAccount);
        assertThrows(ValidationException.class, () -> loanService.updateLoan(loan.getId(), req(UUID.randomUUID(), true)));
        assertEquals(oldAccount, loan.getPaymentAccount());
    }

    @Test
    void clearingALoanWithoutAccountIsNoOp() {
        Loan loan = loan(null);
        loanService.updateLoan(loan.getId(), req(null, true));
        assertNull(loan.getPaymentAccount());
    }

    private UpdateLoanRequest req(UUID accountId, Boolean clear) {
        return new UpdateLoanRequest(null, null, null, null, accountId, clear, null, null, null, null, null, null, null, null);
    }

    private Account bank(UUID ownerId) {
        User owner = new User();
        owner.setId(ownerId);
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setUser(owner);
        a.setType(AccountType.bank_account);
        return a;
    }

    private Loan loan(Account account) {
        User owner = new User();
        owner.setId(USER_ID);
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
        loan.setPaymentAccount(account);
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        return loan;
    }
}
