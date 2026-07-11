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

    @Test
    void testBatchReview_aggregationOfResults() {
        TransactionService mockedSelf = mock(TransactionService.class);
        TransactionService localService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                mockedSelf
        );

        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        when(mockedSelf.attemptReviewItem(eq(idA), any(), any(), any())).thenReturn("SUCCESS");
        when(mockedSelf.attemptReviewItem(eq(idB), any(), any(), any())).thenReturn("SKIPPED");
        when(mockedSelf.attemptReviewItem(eq(idC), any(), any(), any())).thenReturn("FAILURE:NOT_FOUND");

        var response = localService.batchReview(List.of(idA, idB, idC), ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED));

        assertEquals(List.of(idA.toString()), response.succeededIds());
        assertEquals(List.of(idB.toString()), response.skippedIds());
        assertEquals(1, response.failures().size());
        assertEquals(idC.toString(), response.failures().get(0).id());
        assertEquals("NOT_FOUND", response.failures().get(0).reason());
    }

    @Test
    void testBatchReview_noRetryForDeterministicFailures() {
        TransactionService mockedSelf = mock(TransactionService.class);
        TransactionService localService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                mockedSelf
        );

        UUID idNotFound = UUID.randomUUID();
        UUID idNotOwned = UUID.randomUUID();
        UUID idValidation = UUID.randomUUID();

        when(mockedSelf.attemptReviewItem(eq(idNotFound), any(), any(), any())).thenReturn("FAILURE:NOT_FOUND");
        when(mockedSelf.attemptReviewItem(eq(idNotOwned), any(), any(), any())).thenReturn("FAILURE:NOT_OWNED");
        when(mockedSelf.attemptReviewItem(eq(idValidation), any(), any(), any())).thenReturn("FAILURE:VALIDATION_ERROR");

        var response = localService.batchReview(List.of(idNotFound, idNotOwned, idValidation), ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED));

        verify(mockedSelf, times(1)).attemptReviewItem(eq(idNotFound), any(), any(), any());
        verify(mockedSelf, times(1)).attemptReviewItem(eq(idNotOwned), any(), any(), any());
        verify(mockedSelf, times(1)).attemptReviewItem(eq(idValidation), any(), any(), any());

        assertEquals(3, response.failures().size());
        assertTrue(response.failures().stream().allMatch(f -> f.reason().equals("NOT_FOUND") || f.reason().equals("NOT_OWNED") || f.reason().equals("ERROR")));
    }

    @Test
    void testBatchReview_retryOnceOnUnexpectedError_thenSucceed() {
        TransactionService mockedSelf = mock(TransactionService.class);
        TransactionService localService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                mockedSelf
        );

        UUID idA = UUID.randomUUID();
        when(mockedSelf.attemptReviewItem(eq(idA), any(), any(), any()))
                .thenReturn("FAILURE:ERROR")
                .thenReturn("SUCCESS");

        var response = localService.batchReview(List.of(idA), ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED));

        verify(mockedSelf, times(2)).attemptReviewItem(eq(idA), any(), any(), any());
        assertEquals(List.of(idA.toString()), response.succeededIds());
        assertTrue(response.failures().isEmpty());
    }

    @Test
    void testBatchReview_retryOnceOnUnexpectedError_thenFail() {
        TransactionService mockedSelf = mock(TransactionService.class);
        TransactionService localService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                mockedSelf
        );

        UUID idA = UUID.randomUUID();
        when(mockedSelf.attemptReviewItem(eq(idA), any(), any(), any()))
                .thenReturn("FAILURE:ERROR")
                .thenReturn("FAILURE:ERROR");

        var response = localService.batchReview(List.of(idA), ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED));

        verify(mockedSelf, times(2)).attemptReviewItem(eq(idA), any(), any(), any());
        assertTrue(response.succeededIds().isEmpty());
        assertEquals(1, response.failures().size());
        assertEquals("ERROR", response.failures().get(0).reason());
    }

    @Test
    void testBatchReview_itemExceptionRecovery() {
        TransactionService mockedSelf = mock(TransactionService.class);
        TransactionService localService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                mockedSelf
        );

        UUID idA = UUID.randomUUID();
        when(mockedSelf.attemptReviewItem(eq(idA), any(), any(), any()))
                .thenThrow(new RuntimeException("Commit fail"))
                .thenReturn("SUCCESS");

        var response = localService.batchReview(List.of(idA), ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED));

        verify(mockedSelf, times(2)).attemptReviewItem(eq(idA), any(), any(), any());
        assertEquals(List.of(idA.toString()), response.succeededIds());
        assertTrue(response.failures().isEmpty());
    }

    @Test
    void testBatchDelete_aggregationAndRetry() {
        TransactionService mockedSelf = mock(TransactionService.class);
        TransactionService localService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                mockedSelf
        );

        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();

        when(mockedSelf.attemptDeleteItem(eq(idA), any()))
                .thenReturn("FAILURE:ERROR")
                .thenReturn("SUCCESS");
        when(mockedSelf.attemptDeleteItem(eq(idB), any()))
                .thenReturn("FAILURE:NOT_OWNED");

        var response = localService.batchDelete(List.of(idA, idB));

        verify(mockedSelf, times(2)).attemptDeleteItem(eq(idA), any());
        verify(mockedSelf, times(1)).attemptDeleteItem(eq(idB), any());

        assertEquals(List.of(idA.toString()), response.succeededIds());
        assertEquals(1, response.failures().size());
        assertEquals("NOT_OWNED", response.failures().get(0).reason());
    }
}
