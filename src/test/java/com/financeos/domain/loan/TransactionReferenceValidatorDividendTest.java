package com.financeos.domain.loan;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Dividend-row rules added to the validator; the loan/lending rules keep their own test class. */
class TransactionReferenceValidatorDividendTest {

    private TransactionRepository transactionRepository;
    private LoanEventRepository loanEventRepository;
    private LoanPaymentRepository loanPaymentRepository;
    private LoanChargeRepository loanChargeRepository;
    private LendingRepository lendingRepository;
    private DividendRepository dividendRepository;

    private TransactionReferenceValidator validator;

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        loanEventRepository = mock(LoanEventRepository.class);
        loanPaymentRepository = mock(LoanPaymentRepository.class);
        loanChargeRepository = mock(LoanChargeRepository.class);
        lendingRepository = mock(LendingRepository.class);
        dividendRepository = mock(DividendRepository.class);
        validator = new TransactionReferenceValidator(transactionRepository, loanEventRepository, loanPaymentRepository,
                loanChargeRepository, lendingRepository, dividendRepository);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Transaction owned(UUID id, TransactionType type) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setUser(user);
        t.setType(type);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    // --- validateForDividend ------------------------------------------------------------------------

    @Test
    void validateForDividend_nullIdReturnsNullWithoutLookups() {
        assertNull(validator.validateForDividend(null));
        verify(transactionRepository, never()).findById(any());
    }

    @Test
    void validateForDividend_unknownTransactionIs400() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ValidationException.class, () -> validator.validateForDividend(id));
    }

    @Test
    void validateForDividend_foreignTransactionIs400() {
        UUID id = UUID.randomUUID();
        Transaction t = owned(id, TransactionType.CREDIT);
        User other = new User();
        other.setId(UUID.randomUUID());
        t.setUser(other);
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForDividend(id));
        assertTrue(ex.getMessage().contains("does not belong"));
    }

    @Test
    void validateForDividend_rejectsLoanReferencedTransaction() {
        UUID id = UUID.randomUUID();
        owned(id, TransactionType.CREDIT);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(true);
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForDividend(id));
        assertTrue(ex.getMessage().contains("loan record"));
    }

    @Test
    void validateForDividend_rejectsLendingReferencedTransaction() {
        UUID id = UUID.randomUUID();
        owned(id, TransactionType.CREDIT);
        when(lendingRepository.existsByTransaction_Id(id)).thenReturn(true);
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForDividend(id));
        assertTrue(ex.getMessage().contains("lending record"));
    }

    @Test
    void validateForDividend_rejectsDebits() {
        UUID id = UUID.randomUUID();
        owned(id, TransactionType.DEBIT);
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForDividend(id));
        assertTrue(ex.getMessage().contains("CREDIT"));
    }

    @Test
    void validateForDividend_allowsACreditAlreadyUsedByAnotherDividend() {
        UUID id = UUID.randomUUID();
        Transaction t = owned(id, TransactionType.CREDIT);
        when(dividendRepository.existsByTransaction_Id(id)).thenReturn(true);
        assertSame(t, validator.validateForDividend(id));
    }

    @Test
    void validateForDividend_happyPathReturnsTheTransaction() {
        UUID id = UUID.randomUUID();
        Transaction t = owned(id, TransactionType.CREDIT);
        assertSame(t, validator.validateForDividend(id));
    }

    // --- cross-module exclusivity ------------------------------------------------------------------

    @Test
    void validateForLending_rejectsDividendReferencedTransaction() {
        UUID id = UUID.randomUUID();
        owned(id, TransactionType.CREDIT);
        when(dividendRepository.existsByTransaction_Id(id)).thenReturn(true);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateForLending(id, LendingDirection.borrowed));
        assertTrue(ex.getMessage().contains("dividend"));
    }

    @Test
    void validateForLoan_rejectsDividendReferencedTransactionWithADividendMessage() {
        UUID id = UUID.randomUUID();
        owned(id, TransactionType.DEBIT);
        when(dividendRepository.existsByTransaction_Id(id)).thenReturn(true);
        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForLoan(id));
        assertTrue(ex.getMessage().contains("dividend"));
    }

    @Test
    void isTransactionReferenced_countsDividendRows() {
        UUID id = UUID.randomUUID();
        when(dividendRepository.existsByTransaction_Id(id)).thenReturn(true);
        assertTrue(validator.isTransactionReferenced(id));
        assertTrue(validator.isReferencedByDividend(id));
        assertFalse(validator.isReferencedByDividend(null));
    }

    @Test
    void isTransactionReferenced_falseWhenNoTableReferencesIt() {
        assertFalse(validator.isTransactionReferenced(UUID.randomUUID()));
    }
}
