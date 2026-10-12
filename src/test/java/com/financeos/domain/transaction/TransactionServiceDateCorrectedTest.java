package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.UpdateTransactionRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DATE_CORRECTED (an email-alert date the server capped to the sent day) is settled when the user
 * sets the date themselves, and only then. Uses the real {@link ReviewStatusManager}.
 */
class TransactionServiceDateCorrectedTest {

    private static final LocalDate CAPPED = LocalDate.of(2026, 9, 21);

    private TransactionRepository transactionRepository;
    private TransactionService transactionService;
    private Transaction txn;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        transactionService = new TransactionService(transactionRepository, mock(AccountRepository.class),
                mock(CategoryRepository.class), mock(UserRepository.class), new ReviewStatusManager(),
                mock(CategorizationService.class), null);

        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        User user = new User();
        user.setId(userId);
        Account account = new Account();
        account.setId(UUID.randomUUID());

        txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(user);
        txn.setAccount(account);
        txn.setDate(CAPPED);
        txn.setAmount(new BigDecimal("209.70"));
        txn.setType(TransactionType.DEBIT);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UpdateTransactionRequest edit(LocalDate date) {
        // The client echoes the stored review status on every save.
        return new UpdateTransactionRequest(date, new BigDecimal("-209.70"), "Maritime", null, false, null,
                ReviewType.NEEDS_REVIEW, null, null, null);
    }

    @Test
    void changingTheDateClearsTheFlagAndKeepsOtherReasons() {
        txn.setReviewReasons(new HashSet<>(Set.of(ReviewReason.UNRECONCILED, ReviewReason.DATE_CORRECTED)));

        transactionService.updateTransaction(txn.getId(), edit(LocalDate.of(2026, 9, 20)));

        assertEquals(LocalDate.of(2026, 9, 20), txn.getDate());
        assertEquals(Set.of(ReviewReason.UNRECONCILED), txn.getReviewReasons());
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
    }

    @Test
    void changingTheDateWhenItIsTheOnlyReasonMarksReviewed() {
        // A statement already reconciled the alert, so the date flag is all that is left. The echoed
        // NEEDS_REVIEW must not try to re-enter review with no reasons.
        txn.setReviewReasons(new HashSet<>(Set.of(ReviewReason.DATE_CORRECTED)));

        assertDoesNotThrow(() -> transactionService.updateTransaction(txn.getId(), edit(LocalDate.of(2026, 9, 20))));

        assertTrue(txn.getReviewReasons().isEmpty());
        assertEquals(ReviewType.MANUALLY_REVIEWED, txn.getReviewType());
    }

    @Test
    void savingWithTheSameDateKeepsTheFlag() {
        txn.setReviewReasons(new HashSet<>(Set.of(ReviewReason.UNRECONCILED, ReviewReason.DATE_CORRECTED)));

        transactionService.updateTransaction(txn.getId(), edit(CAPPED));

        assertEquals(Set.of(ReviewReason.UNRECONCILED, ReviewReason.DATE_CORRECTED), txn.getReviewReasons());
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
    }
}
