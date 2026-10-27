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
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A credit linked to an EPF withdrawal is referenced: loans, lendings and dividends cannot take it. */
class TransactionReferenceValidatorEpfTest {

    private TransactionRepository transactionRepository;
    private TransactionReferenceValidator validator;
    private Query countQuery;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        validator = new TransactionReferenceValidator(transactionRepository, mock(LoanEventRepository.class),
                mock(LoanPaymentRepository.class), mock(LoanChargeRepository.class), mock(LendingRepository.class),
                mock(DividendRepository.class));
        EntityManager em = mock(EntityManager.class);
        countQuery = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(countQuery);
        when(countQuery.setParameter(anyInt(), any())).thenReturn(countQuery);
        ReflectionTestUtils.setField(validator, "entityManager", em);

        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
        Transaction t = new Transaction();
        t.setId(id);
        t.setUser(user);
        t.setType(TransactionType.CREDIT);
        when(transactionRepository.findById(id)).thenReturn(Optional.of(t));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void epfLinked(boolean linked) {
        when(countQuery.getSingleResult()).thenReturn(linked ? 1L : 0L);
    }

    @Test
    void anEpfLinkedCreditIsRejectedByLoanLendingAndDividendValidation() {
        epfLinked(true);
        assertTrue(assertThrows(ValidationException.class, () -> validator.validateForLoan(id)).getMessage().contains("EPF"));
        assertTrue(assertThrows(ValidationException.class,
                () -> validator.validateForLending(id, LendingDirection.borrowed)).getMessage().contains("EPF"));
        assertTrue(assertThrows(ValidationException.class, () -> validator.validateForDividend(id)).getMessage().contains("EPF"));
        assertTrue(validator.isTransactionReferenced(id));
        assertTrue(validator.isReferencedByEpf(id));
    }

    @Test
    void aCreditWithoutAnEpfLinkStillPasses() {
        epfLinked(false);
        validator.validateForLoan(id);
        validator.validateForLending(id, LendingDirection.borrowed);
        validator.validateForDividend(id);
        assertFalse(validator.isTransactionReferenced(id));
    }
}
