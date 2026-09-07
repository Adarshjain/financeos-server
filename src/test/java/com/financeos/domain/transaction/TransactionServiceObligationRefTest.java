package com.financeos.domain.transaction;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.api.transaction.dto.UpdateTransactionRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.AuditLogger;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.obligation.ObligationKind;
import com.financeos.domain.obligation.ObligationRefService;
import com.financeos.domain.statement.StatementTransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class TransactionServiceObligationRefTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private UserRepository userRepository;
    private ReviewStatusManager reviewStatusManager;
    private CategorizationService categorizationService;
    private TransactionLinkRepository transactionLinkRepository;
    private StatementTransactionRepository statementTransactionRepository;
    private AuditLogger auditLogger;
    private com.financeos.domain.account.card.CardRepository cardRepository;
    private ObligationRefService obligationRefService;

    private TransactionService transactionService;

    private UUID currentUserId;
    private User currentUser;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        userRepository = mock(UserRepository.class);
        reviewStatusManager = new ReviewStatusManager();
        categorizationService = mock(CategorizationService.class);
        transactionLinkRepository = mock(TransactionLinkRepository.class);
        statementTransactionRepository = mock(StatementTransactionRepository.class);
        auditLogger = mock(AuditLogger.class);
        cardRepository = mock(com.financeos.domain.account.card.CardRepository.class);
        obligationRefService = mock(ObligationRefService.class);

        transactionService = new TransactionService(
                transactionRepository,
                accountRepository,
                categoryRepository,
                userRepository,
                reviewStatusManager,
                categorizationService,
                null,
                transactionLinkRepository,
                statementTransactionRepository,
                auditLogger,
                cardRepository,
                obligationRefService,
                null);

        currentUserId = UUID.randomUUID();
        currentUser = new User();
        currentUser.setId(currentUserId);
        UserContext.setCurrentUserId(currentUserId);

        testAccount = new Account();
        testAccount.setId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Transaction createTestTxn(UUID id, TransactionType type, BigDecimal amount) {
        Transaction t = new Transaction(
                testAccount,
                LocalDate.now(),
                amount,
                "Test transaction",
                TransactionSource.manual,
                type,
                false,
                false);
        t.setId(id);
        t.setUser(currentUser);
        t.setReviewType(ReviewType.NA);
        t.setReviewReasons(new java.util.HashSet<>());
        return t;
    }

    private UpdateTransactionRequest updateRequest(BigDecimal signedAmount) {
        return new UpdateTransactionRequest(
                LocalDate.now(), signedAmount, "Updated description", null, null, null, null, null, null);
    }

    // --- updateTransaction: direction-flip guard --------------------------------------------

    @Test
    void updateTransaction_signFlipWithRefs_throwsAndDoesNotSave() {
        UUID id = UUID.randomUUID();
        Transaction existing = createTestTxn(id, TransactionType.DEBIT, new BigDecimal("100.00"));
        when(transactionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(obligationRefService.hasRefs(id)).thenReturn(true);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> transactionService.updateTransaction(id, updateRequest(new BigDecimal("50.00"))));
        assertTrue(ex.getMessage().contains("unlink it before changing its direction"));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateTransaction_signFlipWithoutRefs_allowed() {
        UUID id = UUID.randomUUID();
        Transaction existing = createTestTxn(id, TransactionType.DEBIT, new BigDecimal("100.00"));
        when(transactionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(obligationRefService.hasRefs(id)).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        Transaction saved = transactionService.updateTransaction(id, updateRequest(new BigDecimal("50.00")));

        assertEquals(TransactionType.CREDIT, saved.getType());
        verify(transactionRepository).save(existing);
    }

    @Test
    void updateTransaction_sameSignWithRefs_allowedAndHasRefsSkipped() {
        UUID id = UUID.randomUUID();
        Transaction existing = createTestTxn(id, TransactionType.DEBIT, new BigDecimal("100.00"));
        when(transactionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        Transaction saved = transactionService.updateTransaction(id, updateRequest(new BigDecimal("-75.00")));

        assertEquals(TransactionType.DEBIT, saved.getType());
        verify(transactionRepository).save(existing);
        // Java short-circuits `type != transaction.getType() && obligationRefService.hasRefs(id)`;
        // since the type is unchanged here, hasRefs must never be consulted.
        verify(obligationRefService, never()).hasRefs(any());
    }

    // --- mergeTransactions: compatibility gate + repoint ordering -----------------------------

    @Test
    void mergeTransactions_incompatible_throwsAndSkipsRepointAndDelete() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();
        Transaction kept = createTestTxn(keepId, TransactionType.DEBIT, new BigDecimal("100.00"));
        Transaction deleted = createTestTxn(deleteId, TransactionType.DEBIT, new BigDecimal("100.00"));

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(obligationRefService.checkMergeCompatibility(kept, deleted)).thenReturn("unlink one before merging");

        ValidationException ex = assertThrows(ValidationException.class,
                () -> transactionService.mergeTransactions(keepId, deleteId));
        assertEquals("unlink one before merging", ex.getMessage());

        verify(obligationRefService, never()).repoint(any(), any());
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    void mergeTransactions_compatible_repointsBeforeDelete() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();
        Transaction kept = createTestTxn(keepId, TransactionType.DEBIT, new BigDecimal("100.00"));
        Transaction deleted = createTestTxn(deleteId, TransactionType.DEBIT, new BigDecimal("100.00"));

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(obligationRefService.checkMergeCompatibility(kept, deleted)).thenReturn(null);
        when(obligationRefService.repoint(deleted, kept)).thenReturn(List.of("EMI #2 · HDFC Home Loan"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.mergeTransactions(keepId, deleteId);

        InOrder inOrder = inOrder(obligationRefService, transactionRepository);
        inOrder.verify(obligationRefService).repoint(deleted, kept);
        inOrder.verify(transactionRepository).delete(deleted);
    }

    @Test
    void mergeTransactions_refsMoved_auditsObligationRefsRepointed() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();
        Transaction kept = createTestTxn(keepId, TransactionType.DEBIT, new BigDecimal("100.00"));
        Transaction deleted = createTestTxn(deleteId, TransactionType.DEBIT, new BigDecimal("100.00"));

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(obligationRefService.checkMergeCompatibility(kept, deleted)).thenReturn(null);
        when(obligationRefService.repoint(deleted, kept)).thenReturn(List.of("EMI #2 · HDFC Home Loan"));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.mergeTransactions(keepId, deleteId);

        verify(auditLogger).mutation(eq("Transaction"), eq(keepId), eq("OBLIGATION_REFS_REPOINTED"),
                any(), any(), anyList(), any(), any(), any());
    }

    @Test
    void mergeTransactions_noRefsMoved_doesNotAuditObligationRefsRepointed() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();
        Transaction kept = createTestTxn(keepId, TransactionType.DEBIT, new BigDecimal("100.00"));
        Transaction deleted = createTestTxn(deleteId, TransactionType.DEBIT, new BigDecimal("100.00"));

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(obligationRefService.checkMergeCompatibility(kept, deleted)).thenReturn(null);
        when(obligationRefService.repoint(deleted, kept)).thenReturn(List.of());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.mergeTransactions(keepId, deleteId);

        verify(auditLogger, never()).mutation(eq("Transaction"), any(), eq("OBLIGATION_REFS_REPOINTED"),
                any(), any(), anyList(), any(), any(), any());
    }

    // --- deleteTransaction: orphan logging, non-blocking -------------------------------------

    @Test
    void deleteTransaction_withRefs_stillDeletes() {
        UUID id = UUID.randomUUID();
        Transaction existing = createTestTxn(id, TransactionType.DEBIT, new BigDecimal("100.00"));
        when(transactionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(obligationRefService.refsFor(List.of(id))).thenReturn(
                Map.of(id, List.of(new ObligationRef(ObligationKind.LOAN_PAYMENT, UUID.randomUUID(), UUID.randomUUID(), "EMI #1 · Loan", BigDecimal.TEN))));

        assertDoesNotThrow(() -> transactionService.deleteTransaction(id));

        verify(obligationRefService).refsFor(List.of(id));
        verify(transactionRepository).delete(existing);
    }

    @Test
    void deleteTransaction_withNullObligationRefService_stillWorks() {
        TransactionService serviceWithoutObligationRefs = new TransactionService(
                transactionRepository, accountRepository, categoryRepository, userRepository,
                reviewStatusManager, categorizationService, null, transactionLinkRepository,
                statementTransactionRepository, auditLogger, cardRepository, null);

        UUID id = UUID.randomUUID();
        Transaction existing = createTestTxn(id, TransactionType.DEBIT, new BigDecimal("100.00"));
        when(transactionRepository.findById(id)).thenReturn(Optional.of(existing));

        assertDoesNotThrow(() -> serviceWithoutObligationRefs.deleteTransaction(id));
        verify(transactionRepository).delete(existing);
    }

    // --- batchDelete: refsFor consulted once with owned ids ------------------------------------

    @Test
    void batchDelete_refsForCalledOnceWithOwnedFoundIds() {
        UUID ownedId = UUID.randomUUID();
        UUID notFoundId = UUID.randomUUID();
        UUID foreignId = UUID.randomUUID();

        Transaction owned = createTestTxn(ownedId, TransactionType.DEBIT, new BigDecimal("100.00"));
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        Transaction foreign = createTestTxn(foreignId, TransactionType.DEBIT, new BigDecimal("50.00"));
        foreign.setUser(otherUser);

        when(transactionRepository.findAllByIdIn(List.of(ownedId, notFoundId, foreignId)))
                .thenReturn(List.of(owned, foreign));
        when(obligationRefService.refsFor(List.of(ownedId))).thenReturn(Map.of());

        transactionService.batchDelete(List.of(ownedId, notFoundId, foreignId));

        verify(obligationRefService, times(1)).refsFor(List.of(ownedId));
    }
}
