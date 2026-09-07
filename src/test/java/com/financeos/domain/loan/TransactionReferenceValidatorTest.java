package com.financeos.domain.loan;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
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

class TransactionReferenceValidatorTest {

    private TransactionRepository transactionRepository;
    private LoanEventRepository loanEventRepository;
    private LoanPaymentRepository loanPaymentRepository;
    private LoanChargeRepository loanChargeRepository;
    private LendingRepository lendingRepository;

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

        validator = new TransactionReferenceValidator(
                transactionRepository, loanEventRepository, loanPaymentRepository, loanChargeRepository, lendingRepository);

        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Transaction ownedTransaction(UUID id, TransactionType type) {
        Transaction t = new Transaction();
        t.setId(id);
        t.setUser(user);
        t.setType(type);
        return t;
    }

    private void mockClean(UUID id, Transaction t) {
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanChargeRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(lendingRepository.existsByTransaction_Id(id)).thenReturn(false);
    }

    // --- validateForLoan -----------------------------------------------------------------

    @Test
    void validateForLoan_nullId_returnsNullWithoutRepoCall() {
        assertNull(validator.validateForLoan(null));
        verify(transactionRepository, never()).findById(any());
    }

    @Test
    void validateForLoan_unknownId_throws() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ValidationException.class, () -> validator.validateForLoan(id));
    }

    @Test
    void validateForLoan_foreignUser_throws() {
        UUID id = UUID.randomUUID();
        User other = new User();
        other.setId(UUID.randomUUID());
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        t.setUser(other);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));

        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForLoan(id));
        assertTrue(ex.getMessage().contains("does not belong to the current user"));
    }

    @Test
    void validateForLoan_referencedByLoanEvent_throws() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        mockClean(id, t);
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForLoan(id));
        assertTrue(ex.getMessage().contains("already linked to a loan or lending record"));
    }

    @Test
    void validateForLoan_referencedByLoanPayment_throws() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        mockClean(id, t);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForLoan(id));
        assertTrue(ex.getMessage().contains("already linked to a loan or lending record"));
    }

    @Test
    void validateForLoan_referencedByLoanCharge_throws() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        mockClean(id, t);
        when(loanChargeRepository.existsByTransaction_Id(id)).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForLoan(id));
        assertTrue(ex.getMessage().contains("already linked to a loan or lending record"));
    }

    @Test
    void validateForLoan_referencedByLending_throws() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        mockClean(id, t);
        when(lendingRepository.existsByTransaction_Id(id)).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class, () -> validator.validateForLoan(id));
        assertTrue(ex.getMessage().contains("already linked to a loan or lending record"));
    }

    @Test
    void validateForLoan_clean_returnsTransaction() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        mockClean(id, t);

        assertSame(t, validator.validateForLoan(id));
    }

    // --- validateForLending ----------------------------------------------------------------

    @Test
    void validateForLending_nullId_returnsNullWithoutRepoCall() {
        assertNull(validator.validateForLending(null, LendingDirection.lent));
        verify(transactionRepository, never()).findById(any());
    }

    @Test
    void validateForLending_unknownId_throws() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ValidationException.class, () -> validator.validateForLending(id, LendingDirection.lent));
    }

    @Test
    void validateForLending_foreignUser_throws() {
        UUID id = UUID.randomUUID();
        User other = new User();
        other.setId(UUID.randomUUID());
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        t.setUser(other);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));

        assertThrows(ValidationException.class, () -> validator.validateForLending(id, LendingDirection.lent));
    }

    @Test
    void validateForLending_referencedByLoan_throwsWithLoanRecordMessage() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateForLending(id, LendingDirection.lent));
        assertTrue(ex.getMessage().contains("loan record"));
    }

    @Test
    void validateForLending_referencedByAnotherLending_allowed() {
        // Split bills: another lending row already pointing at this transaction is not a rejection reason.
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanChargeRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(lendingRepository.existsByTransaction_Id(id)).thenReturn(true);

        assertSame(t, validator.validateForLending(id, LendingDirection.lent));
    }

    @Test
    void validateForLending_lentDirectionOnCredit_throws() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.CREDIT);
        mockClean(id, t);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateForLending(id, LendingDirection.lent));
        assertTrue(ex.getMessage().contains("must link a DEBIT"));
    }

    @Test
    void validateForLending_borrowedDirectionOnDebit_throws() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        mockClean(id, t);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateForLending(id, LendingDirection.borrowed));
        assertTrue(ex.getMessage().contains("must link a CREDIT"));
    }

    @Test
    void validateForLending_lentDirectionOnDebit_ok() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.DEBIT);
        mockClean(id, t);

        assertSame(t, validator.validateForLending(id, LendingDirection.lent));
    }

    @Test
    void validateForLending_borrowedDirectionOnCredit_ok() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.CREDIT);
        mockClean(id, t);

        assertSame(t, validator.validateForLending(id, LendingDirection.borrowed));
    }

    @Test
    void validateForLending_nullDirection_skipsDirectionCheck() {
        UUID id = UUID.randomUUID();
        Transaction t = ownedTransaction(id, TransactionType.CREDIT);
        mockClean(id, t);

        assertSame(t, validator.validateForLending(id, null));
    }

    // --- expectedTypeFor -------------------------------------------------------------------

    @Test
    void expectedTypeFor_lent_isDebit() {
        assertEquals(TransactionType.DEBIT, TransactionReferenceValidator.expectedTypeFor(LendingDirection.lent));
    }

    @Test
    void expectedTypeFor_borrowed_isCredit() {
        assertEquals(TransactionType.CREDIT, TransactionReferenceValidator.expectedTypeFor(LendingDirection.borrowed));
    }

    @Test
    void expectedTypeFor_null_defaultsToDebit() {
        assertEquals(TransactionType.DEBIT, TransactionReferenceValidator.expectedTypeFor(null));
    }

    // --- isReferencedByLoan / isReferencedByLending / isTransactionReferenced --------------

    @Test
    void isReferencedByLoan_nullId_falseWithoutRepoCalls() {
        assertFalse(validator.isReferencedByLoan(null));
        verifyNoInteractions(loanEventRepository, loanPaymentRepository, loanChargeRepository);
    }

    @Test
    void isReferencedByLoan_allFalse_false() {
        UUID id = UUID.randomUUID();
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanChargeRepository.existsByTransaction_Id(id)).thenReturn(false);
        assertFalse(validator.isReferencedByLoan(id));
    }

    @Test
    void isReferencedByLoan_eventTrue_trueAndShortCircuits() {
        UUID id = UUID.randomUUID();
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(true);
        assertTrue(validator.isReferencedByLoan(id));
        verifyNoInteractions(loanPaymentRepository, loanChargeRepository);
    }

    @Test
    void isReferencedByLoan_paymentTrue_true() {
        UUID id = UUID.randomUUID();
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(true);
        assertTrue(validator.isReferencedByLoan(id));
    }

    @Test
    void isReferencedByLoan_chargeTrue_true() {
        UUID id = UUID.randomUUID();
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanChargeRepository.existsByTransaction_Id(id)).thenReturn(true);
        assertTrue(validator.isReferencedByLoan(id));
    }

    @Test
    void isReferencedByLending_nullId_falseWithoutRepoCall() {
        assertFalse(validator.isReferencedByLending(null));
        verifyNoInteractions(lendingRepository);
    }

    @Test
    void isReferencedByLending_true() {
        UUID id = UUID.randomUUID();
        when(lendingRepository.existsByTransaction_Id(id)).thenReturn(true);
        assertTrue(validator.isReferencedByLending(id));
    }

    @Test
    void isReferencedByLending_false() {
        UUID id = UUID.randomUUID();
        when(lendingRepository.existsByTransaction_Id(id)).thenReturn(false);
        assertFalse(validator.isReferencedByLending(id));
    }

    @Test
    void isTransactionReferenced_nullId_false() {
        assertFalse(validator.isTransactionReferenced(null));
    }

    @Test
    void isTransactionReferenced_loanTrue_trueAndSkipsLendingCheck() {
        UUID id = UUID.randomUUID();
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(true);
        assertTrue(validator.isTransactionReferenced(id));
        verifyNoInteractions(lendingRepository);
    }

    @Test
    void isTransactionReferenced_lendingTrue_true() {
        UUID id = UUID.randomUUID();
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanChargeRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(lendingRepository.existsByTransaction_Id(id)).thenReturn(true);
        assertTrue(validator.isTransactionReferenced(id));
    }

    @Test
    void isTransactionReferenced_bothFalse_false() {
        UUID id = UUID.randomUUID();
        when(loanEventRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanPaymentRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(loanChargeRepository.existsByTransaction_Id(id)).thenReturn(false);
        when(lendingRepository.existsByTransaction_Id(id)).thenReturn(false);
        assertFalse(validator.isTransactionReferenced(id));
    }
}
