package com.financeos.domain.transaction;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;

class TransactionServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private UserRepository userRepository;
    private ReviewStatusManager reviewStatusManager;
    private CategorizationService categorizationService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        userRepository = mock(UserRepository.class);
        reviewStatusManager = mock(ReviewStatusManager.class);
        categorizationService = mock(CategorizationService.class);

        transactionService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                null
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testAttemptReviewItem_whenNotFound_returnsNotFound() {
        UUID txnId = UUID.randomUUID();
        when(transactionRepository.findById(txnId)).thenReturn(Optional.empty());

        String result = transactionService.attemptReviewItem(txnId, ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED), UUID.randomUUID());

        assertEquals("FAILURE:NOT_FOUND", result);
    }

    @Test
    void testAttemptReviewItem_whenNotOwned_returnsNotOwned() {
        UUID txnId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        User owner = new User();
        owner.setId(ownerId);

        Transaction txn = new Transaction();
        txn.setId(txnId);
        txn.setUser(owner);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));

        String result = transactionService.attemptReviewItem(txnId, ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED), currentUserId);

        assertEquals("FAILURE:NOT_OWNED", result);
    }

    @Test
    void testAttemptReviewItem_whenOwnedAndMatchesReason_clearsReasonAndReturnsSuccess() {
        UUID txnId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();

        User owner = new User();
        owner.setId(currentUserId);

        Transaction txn = new Transaction();
        txn.setId(txnId);
        txn.setUser(owner);
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.UNRECONCILED)));

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));

        String result = transactionService.attemptReviewItem(txnId, ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED), currentUserId);

        assertEquals("SUCCESS", result);
        verify(reviewStatusManager).clearReason(txn, ReviewReason.UNRECONCILED, ReviewType.MANUALLY_REVIEWED);
        verify(transactionRepository).save(txn);
    }

    @Test
    void testAttemptReviewItem_whenOwnedAndNoReasonMatches_returnsSkipped() {
        UUID txnId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();

        User owner = new User();
        owner.setId(currentUserId);

        Transaction txn = new Transaction();
        txn.setId(txnId);
        txn.setUser(owner);
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.CATEGORY_UNVERIFIED)));

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));

        String result = transactionService.attemptReviewItem(txnId, ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED), currentUserId);

        assertEquals("SKIPPED", result);
        verifyNoInteractions(reviewStatusManager);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void testAttemptDeleteItem_whenNotOwned_returnsNotOwned() {
        UUID txnId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        User owner = new User();
        owner.setId(ownerId);

        Transaction txn = new Transaction();
        txn.setId(txnId);
        txn.setUser(owner);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));

        String result = transactionService.attemptDeleteItem(txnId, currentUserId);

        assertEquals("FAILURE:NOT_OWNED", result);
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    void testAttemptDeleteItem_whenOwned_deletesAndReturnsSuccess() {
        UUID txnId = UUID.randomUUID();
        UUID currentUserId = UUID.randomUUID();

        User owner = new User();
        owner.setId(currentUserId);

        Transaction txn = new Transaction();
        txn.setId(txnId);
        txn.setUser(owner);

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));

        String result = transactionService.attemptDeleteItem(txnId, currentUserId);

        assertEquals("SUCCESS", result);
        verify(transactionRepository).delete(txn);
    }
}
