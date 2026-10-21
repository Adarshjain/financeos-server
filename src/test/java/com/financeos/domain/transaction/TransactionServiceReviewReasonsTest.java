package com.financeos.domain.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.financeos.api.transaction.dto.UpdateTransactionRequest;
import com.financeos.core.exception.ValidationException;
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

class TransactionServiceReviewReasonsTest {

    private TransactionRepository transactionRepository;
    private TransactionService service;
    private User user;
    private Transaction txn;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        service = new TransactionService(transactionRepository, mock(AccountRepository.class),
                mock(CategoryRepository.class), mock(UserRepository.class), new ReviewStatusManager(),
                mock(CategorizationService.class), null);
        UUID uid = UUID.randomUUID();
        UserContext.setCurrentUserId(uid);
        user = new User();
        user.setId(uid);
        Account account = new Account();
        account.setId(UUID.randomUUID());
        txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(user);
        txn.setAccount(account);
        txn.setDate(LocalDate.now());
        txn.setAmount(new BigDecimal("10.00"));
        txn.setType(TransactionType.DEBIT);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.UNRECONCILED)));
        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private UpdateTransactionRequest req(ReviewType type, Set<ReviewReason> reasons) {
        return new UpdateTransactionRequest(null, null, LocalDate.now(), new BigDecimal("-10.00"), "Desc", null,
                null, null, type, null, null, null, reasons);
    }

    @Test
    void needsReviewWithReasons_replacesReasonsExactly() {
        service.updateTransaction(txn.getId(), req(ReviewType.NEEDS_REVIEW,
                Set.of(ReviewReason.DATE_CORRECTED, ReviewReason.DUPLICATE_SUSPECT)));
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertEquals(Set.of(ReviewReason.DATE_CORRECTED, ReviewReason.DUPLICATE_SUSPECT), txn.getReviewReasons());
    }

    @Test
    void reviewedTransactionMovedToNeedsReviewWithReasons() {
        txn.setReviewType(ReviewType.MANUALLY_REVIEWED);
        txn.setReviewReasons(new HashSet<>());
        service.updateTransaction(txn.getId(), req(ReviewType.NEEDS_REVIEW, Set.of(ReviewReason.CATEGORY_UNVERIFIED)));
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED), txn.getReviewReasons());
        assertNull(txn.getReviewedAt());
    }

    @Test
    void needsReviewWithEmptyReasons_rejected() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.updateTransaction(txn.getId(), req(ReviewType.NEEDS_REVIEW, Set.of())));
        assertEquals("Pick at least one reason it needs review", ex.getMessage());
        assertEquals(Set.of(ReviewReason.UNRECONCILED), txn.getReviewReasons());
    }

    @Test
    void needsReviewWithNullReasons_keepsExistingBehaviour() {
        service.updateTransaction(txn.getId(), req(ReviewType.NEEDS_REVIEW, null));
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertEquals(Set.of(ReviewReason.UNRECONCILED), txn.getReviewReasons());
    }

    @Test
    void otherReviewTypeWithReasons_rejected() {
        assertThrows(ValidationException.class, () -> service.updateTransaction(txn.getId(),
                req(ReviewType.MANUALLY_REVIEWED, Set.of(ReviewReason.UNRECONCILED))));
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
    }

    @Test
    void otherReviewTypeWithEmptyReasons_allowed() {
        service.updateTransaction(txn.getId(), req(ReviewType.MANUALLY_REVIEWED, Set.of()));
        assertEquals(ReviewType.MANUALLY_REVIEWED, txn.getReviewType());
        assertTrue(txn.getReviewReasons().isEmpty());
    }

    @Test
    void batchNeedsReview_readdsReasonsAndSetsStatus() {
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.UNRECONCILED)));
        when(transactionRepository.findAllByIdIn(List.of(txn.getId()))).thenReturn(List.of(txn));

        // approve one reason of two -> stays NEEDS_REVIEW; then approve the last -> reviewed; undo it
        txn.getReviewReasons().add(ReviewReason.DUPLICATE_SUSPECT);
        service.batchReview(List.of(txn.getId()), ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED, ReviewReason.DUPLICATE_SUSPECT));
        assertEquals(ReviewType.MANUALLY_REVIEWED, txn.getReviewType());
        assertTrue(txn.getReviewReasons().isEmpty());

        var res = service.batchReview(List.of(txn.getId()), ReviewType.NEEDS_REVIEW, List.of(ReviewReason.UNRECONCILED));
        assertEquals(1, res.succeededIds().size());
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertEquals(Set.of(ReviewReason.UNRECONCILED), txn.getReviewReasons());
        assertNull(txn.getReviewedAt());
    }

    @Test
    void batchNeedsReview_addsToExistingReasons() {
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.DUPLICATE_SUSPECT)));
        when(transactionRepository.findAllByIdIn(List.of(txn.getId()))).thenReturn(List.of(txn));
        service.batchReview(List.of(txn.getId()), ReviewType.NEEDS_REVIEW, List.of(ReviewReason.UNRECONCILED));
        assertEquals(Set.of(ReviewReason.DUPLICATE_SUSPECT, ReviewReason.UNRECONCILED), txn.getReviewReasons());
    }

    @Test
    void batchNeedsReview_withoutReasons_keepsOldBehaviour() {
        when(transactionRepository.findAllByIdIn(List.of(txn.getId()))).thenReturn(List.of(txn));
        var res = service.batchReview(List.of(txn.getId()), ReviewType.NEEDS_REVIEW, null);
        assertEquals(1, res.succeededIds().size());
        assertEquals(Set.of(ReviewReason.UNRECONCILED), txn.getReviewReasons());
    }
}
