package com.financeos.domain.transaction;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.time.LocalDate;
import java.math.BigDecimal;
import com.financeos.api.transaction.dto.UpdateTransactionRequest;
import com.financeos.domain.account.Account;

class TransactionServiceTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private UserRepository userRepository;
    private ReviewStatusManager reviewStatusManager;
    private CategorizationService categorizationService;

    private TransactionService transactionService;

    private UUID currentUserId;

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

        currentUserId = UUID.randomUUID();
        UserContext.setCurrentUserId(currentUserId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Transaction ownedTxn(UUID id, Set<ReviewReason> reasons) {
        return txnForUser(id, currentUserId, reasons);
    }

    private Transaction txnForUser(UUID id, UUID ownerId, Set<ReviewReason> reasons) {
        User owner = new User();
        owner.setId(ownerId);
        Transaction txn = new Transaction();
        txn.setId(id);
        txn.setUser(owner);
        if (reasons != null) {
            txn.setReviewReasons(new HashSet<>(reasons));
        }
        return txn;
    }

    // ---- batchReview ----

    @Test
    void testBatchReview_emptyIds_returnsEmptyResponse() {
        var response = transactionService.batchReview(List.of(), ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED));

        assertTrue(response.succeededIds().isEmpty());
        assertTrue(response.skippedIds().isEmpty());
        assertTrue(response.failures().isEmpty());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void testBatchReview_tooManyIds_throwsValidation() {
        List<UUID> ids = IntStream.range(0, 501).mapToObj(i -> UUID.randomUUID()).collect(Collectors.toList());

        assertThrows(ValidationException.class,
                () -> transactionService.batchReview(ids, ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED)));
    }

    @Test
    void testBatchReview_clearingTypeWithoutReasons_throwsValidation() {
        List<UUID> ids = List.of(UUID.randomUUID());

        assertThrows(ValidationException.class,
                () -> transactionService.batchReview(ids, ReviewType.MANUALLY_REVIEWED, List.of()));
    }

    @Test
    void testBatchReview_clearingType_aggregatesSuccessSkipAndFailures() {
        UUID idSuccess = UUID.randomUUID();
        UUID idSkipped = UUID.randomUUID();
        UUID idNotOwned = UUID.randomUUID();
        UUID idNotFound = UUID.randomUUID();

        Transaction success = ownedTxn(idSuccess, Set.of(ReviewReason.UNRECONCILED));
        Transaction skipped = ownedTxn(idSkipped, Set.of(ReviewReason.CATEGORY_UNVERIFIED));
        Transaction notOwned = txnForUser(idNotOwned, UUID.randomUUID(), Set.of(ReviewReason.UNRECONCILED));

        List<UUID> ids = List.of(idSuccess, idSkipped, idNotOwned, idNotFound);
        when(transactionRepository.findAllByIdIn(ids)).thenReturn(List.of(success, skipped, notOwned));

        var response = transactionService.batchReview(ids, ReviewType.MANUALLY_REVIEWED, List.of(ReviewReason.UNRECONCILED));

        assertEquals(List.of(idSuccess.toString()), response.succeededIds());
        assertEquals(List.of(idSkipped.toString()), response.skippedIds());

        Map<String, String> failureReasons = response.failures().stream()
                .collect(Collectors.toMap(f -> f.id(), f -> f.reason()));
        assertEquals("NOT_OWNED", failureReasons.get(idNotOwned.toString()));
        assertEquals("NOT_FOUND", failureReasons.get(idNotFound.toString()));
        assertEquals(2, failureReasons.size());

        // Only the matching-reason txn has its reason cleared and is persisted.
        verify(reviewStatusManager).clearReason(success, ReviewReason.UNRECONCILED, ReviewType.MANUALLY_REVIEWED);
        verify(reviewStatusManager, never()).clearReason(eq(skipped), any(), any());
        verify(transactionRepository).saveAll(argThat((Iterable<Transaction> saved) -> {
            List<Transaction> list = new ArrayList<>();
            saved.forEach(list::add);
            return list.size() == 1 && list.contains(success);
        }));
    }

    @Test
    void testBatchReview_nonClearingType_transitionsWithoutReasonMatching() {
        UUID idA = UUID.randomUUID();
        Transaction txn = ownedTxn(idA, Set.of(ReviewReason.UNRECONCILED));

        List<UUID> ids = List.of(idA);
        when(transactionRepository.findAllByIdIn(ids)).thenReturn(List.of(txn));

        // Non-clearing type: reasons are not required and no skip-gating applies.
        var response = transactionService.batchReview(ids, ReviewType.NEEDS_REVIEW, null);

        assertEquals(List.of(idA.toString()), response.succeededIds());
        assertTrue(response.skippedIds().isEmpty());
        assertTrue(response.failures().isEmpty());
        verify(reviewStatusManager).transitionTo(txn, ReviewType.NEEDS_REVIEW);
        verify(transactionRepository).saveAll(anyList());
    }

    // ---- batchDelete ----

    @Test
    void testBatchDelete_emptyIds_returnsEmptyResponse() {
        var response = transactionService.batchDelete(List.of());

        assertTrue(response.succeededIds().isEmpty());
        assertTrue(response.failures().isEmpty());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void testBatchDelete_aggregatesSuccessAndFailures() {
        UUID idOwned = UUID.randomUUID();
        UUID idNotOwned = UUID.randomUUID();
        UUID idNotFound = UUID.randomUUID();

        Transaction owned = ownedTxn(idOwned, null);
        Transaction notOwned = txnForUser(idNotOwned, UUID.randomUUID(), null);

        List<UUID> ids = List.of(idOwned, idNotOwned, idNotFound);
        when(transactionRepository.findAllByIdIn(ids)).thenReturn(List.of(owned, notOwned));

        var response = transactionService.batchDelete(ids);

        assertEquals(List.of(idOwned.toString()), response.succeededIds());

        Map<String, String> failureReasons = response.failures().stream()
                .collect(Collectors.toMap(f -> f.id(), f -> f.reason()));
        assertEquals("NOT_OWNED", failureReasons.get(idNotOwned.toString()));
        assertEquals("NOT_FOUND", failureReasons.get(idNotFound.toString()));
        assertEquals(2, failureReasons.size());

        verify(transactionRepository).deleteAllByIdInBatch(List.of(idOwned));
    }

    // ---- updateTransaction ----

    @Test
    void testUpdateTransactionMccPreserveAndClear() {
        User user = new User();
        user.setId(currentUserId);

        Account account = new Account();
        account.setId(UUID.randomUUID());

        Transaction txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(user);
        txn.setAccount(account);
        txn.setDate(LocalDate.now());
        txn.setAmount(new BigDecimal("-10.00"));
        txn.setMcc("5411");

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 1. Omit mcc (null) -> preserve 5411
        UpdateTransactionRequest omitRequest = new UpdateTransactionRequest(
                LocalDate.now(),
                new BigDecimal("-10.00"),
                "Desc",
                null,
                null,
                null,
                null,
                null,
                null
        , null);
        transactionService.updateTransaction(txn.getId(), omitRequest);
        assertEquals("5411", txn.getMcc());

        // 2. Pass empty string -> clear mcc to null
        UpdateTransactionRequest clearRequest = new UpdateTransactionRequest(
                LocalDate.now(),
                new BigDecimal("-10.00"),
                "Desc",
                null,
                null,
                null,
                null,
                null,
                ""
        , null);
        transactionService.updateTransaction(txn.getId(), clearRequest);
        assertNull(txn.getMcc());
    }
}
