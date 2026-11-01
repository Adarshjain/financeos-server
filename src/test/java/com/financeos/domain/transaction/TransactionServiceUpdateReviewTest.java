package com.financeos.domain.transaction;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import com.financeos.api.transaction.dto.UpdateTransactionRequest;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Tag;
import com.financeos.domain.category.TagRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Regression coverage for the review-status handling in
 * {@link TransactionService#updateTransaction}. Uses the real
 * {@link ReviewStatusManager} so the actual transition guards are exercised.
 */
class TransactionServiceUpdateReviewTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private TagRepository categoryRepository;
    private UserRepository userRepository;
    private CategorizationService categorizationService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(TagRepository.class);
        userRepository = mock(UserRepository.class);
        categorizationService = mock(CategorizationService.class);

        transactionService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                new ReviewStatusManager(),
                categorizationService,
                null
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void editingUnrelatedField_keepsNeedsReviewWhenCategoriesUnchanged() {
        // Editing an unrelated field (monitoring flag) on a NEEDS_REVIEW txn whose only
        // reason is DUPLICATE_SUSPECT must not clear that reason. The client round-trips
        // the existing tagIds unchanged.
        UUID currentUserId = UUID.randomUUID();
        UserContext.setCurrentUserId(currentUserId);
        User user = new User();
        user.setId(currentUserId);

        Account account = new Account();
        account.setId(UUID.randomUUID());

        Tag cat = new Tag("Groceries", user);
        cat.setId(UUID.randomUUID());

        Transaction txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(user);
        txn.setAccount(account);
        txn.setDate(LocalDate.now());
        txn.setAmount(new BigDecimal("-10.00"));
        txn.setTags(new HashSet<>(List.of(cat)));
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.DUPLICATE_SUSPECT)));

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepository.findAllById(List.of(cat.getId()))).thenReturn(List.of(cat));

        // Toggle monitoring on; resend the unchanged tagIds and current reviewType,
        // exactly as the client does.
        UpdateTransactionRequest request = new UpdateTransactionRequest(
                LocalDate.now(),
                new BigDecimal("-10.00"),
                "Desc",
                List.of(cat.getId()),
                true,
                null,
                ReviewType.NEEDS_REVIEW,
                "Watching this one",
                null
        , null);

        assertDoesNotThrow(() -> transactionService.updateTransaction(txn.getId(), request));
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertTrue(txn.getReviewReasons().contains(ReviewReason.DUPLICATE_SUSPECT));
        assertTrue(txn.isTransactionUnderMonitoring());
    }

    @Test
    void changingTags_leavesTheReviewStateAlone() {
        // Tags are plain labels: changing them says nothing about whether the row was reviewed.
        UUID currentUserId = UUID.randomUUID();
        UserContext.setCurrentUserId(currentUserId);
        User user = new User();
        user.setId(currentUserId);

        Account account = new Account();
        account.setId(UUID.randomUUID());

        Tag oldCat = new Tag("Groceries", user);
        oldCat.setId(UUID.randomUUID());
        Tag newCat = new Tag("Dining", user);
        newCat.setId(UUID.randomUUID());

        Transaction txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(user);
        txn.setAccount(account);
        txn.setDate(LocalDate.now());
        txn.setAmount(new BigDecimal("-10.00"));
        txn.setTags(new HashSet<>(List.of(oldCat)));
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.DUPLICATE_SUSPECT)));

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(categoryRepository.findAllById(List.of(newCat.getId()))).thenReturn(List.of(newCat));

        UpdateTransactionRequest request = new UpdateTransactionRequest(
                LocalDate.now(),
                new BigDecimal("-10.00"),
                "Desc",
                List.of(newCat.getId()),
                null,
                null,
                null,
                null,
                null
        , null);

        transactionService.updateTransaction(txn.getId(), request);
        assertEquals(ReviewType.NEEDS_REVIEW, txn.getReviewType());
        assertTrue(txn.getReviewReasons().contains(ReviewReason.DUPLICATE_SUSPECT));
        assertEquals(Set.of(newCat.getId()), txn.getTags().stream().map(tt -> tt.getTag().getId()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void editingField_echoedReviewType_doesNotDriveTransition() {
        // The edit form always echoes the current reviewType. Re-sending the unchanged
        // value must NOT trigger a transition (which could otherwise throw for NEEDS_REVIEW).
        UUID uid = UUID.randomUUID();
        UserContext.setCurrentUserId(uid);
        ReviewStatusManager mockManager = mock(ReviewStatusManager.class);
        TransactionService service = new TransactionService(
                transactionRepository, accountRepository, categoryRepository,
                userRepository, mockManager, categorizationService, null);

        User user = new User();
        user.setId(uid);

        Transaction txn = baseTxn(user);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.DUPLICATE_SUSPECT)));

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateTransactionRequest request = new UpdateTransactionRequest(
                LocalDate.now(), new BigDecimal("-10.00"), "Desc",
                null, true, null, ReviewType.NEEDS_REVIEW, "watch", null, null);

        service.updateTransaction(txn.getId(), request);

        verify(mockManager, never()).transitionTo(any(), any());
    }

    @Test
    void editingField_changedReviewType_drivesTransition() {
        // An explicit status change (different from the stored value) must still transition.
        UUID uid = UUID.randomUUID();
        UserContext.setCurrentUserId(uid);
        ReviewStatusManager mockManager = mock(ReviewStatusManager.class);
        TransactionService service = new TransactionService(
                transactionRepository, accountRepository, categoryRepository,
                userRepository, mockManager, categorizationService, null);

        User user = new User();
        user.setId(uid);

        Transaction txn = baseTxn(user);
        txn.setReviewType(ReviewType.NEEDS_REVIEW);
        txn.setReviewReasons(new HashSet<>(List.of(ReviewReason.DUPLICATE_SUSPECT)));

        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateTransactionRequest request = new UpdateTransactionRequest(
                LocalDate.now(), new BigDecimal("-10.00"), "Desc",
                null, null, null, ReviewType.NA, null, null, null);

        service.updateTransaction(txn.getId(), request);

        verify(mockManager).transitionTo(txn, ReviewType.NA);
    }

    private Transaction baseTxn(User user) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        Transaction txn = new Transaction();
        txn.setId(UUID.randomUUID());
        txn.setUser(user);
        txn.setAccount(account);
        txn.setDate(LocalDate.now());
        txn.setAmount(new BigDecimal("-10.00"));
        return txn;
    }
}
