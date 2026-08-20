package com.financeos.domain.transaction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.financeos.api.transaction.dto.MergeTransactionsResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.category.Category;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.statement.StatementTransaction;
import com.financeos.domain.statement.StatementTransactionRepository;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

class TransactionMergeTest {

    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private UserRepository userRepository;
    private ReviewStatusManager reviewStatusManager;
    private CategorizationService categorizationService;
    private TransactionLinkRepository transactionLinkRepository;
    private StatementTransactionRepository statementTransactionRepository;

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
                null,
                null
        );

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

    private Transaction createTestTxn(UUID id, Account account, TransactionSource source) {
        Transaction t = new Transaction(
                account,
                LocalDate.now(),
                new BigDecimal("100.00"),
                "Test transaction",
                source,
                TransactionType.DEBIT,
                false,
                false
        );
        t.setId(id);
        t.setUser(currentUser);
        t.setReviewType(ReviewType.NEEDS_REVIEW);
        t.setReviewReasons(new HashSet<>(Set.of(ReviewReason.UNRECONCILED, ReviewReason.DUPLICATE_SUSPECT)));
        return t;
    }

    @Test
    void testMerge_SameIdValidation() {
        UUID id = UUID.randomUUID();
        assertThrows(ValidationException.class, () -> transactionService.mergeTransactions(id, id));
    }

    @Test
    void testMerge_CrossAccountValidation() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        Account acc2 = new Account();
        acc2.setId(UUID.randomUUID());

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        Transaction deleted = createTestTxn(deleteId, acc2, TransactionSource.gmail_transaction_alert);

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> transactionService.mergeTransactions(keepId, deleteId));
        assertTrue(ex.getMessage().contains("different accounts"));
    }

    @Test
    void testMerge_OwnershipValidation() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);
        deleted.setUser(otherUser);

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> transactionService.mergeTransactions(keepId, deleteId));
        assertTrue(ex.getMessage().contains("permission"));
    }

    @Test
    void testMerge_CarryOverEmptyFields() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        kept.setDescription(null);
        kept.setChannel(null);

        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);
        deleted.setDescription("Deleted user note");
        deleted.setChannel(TransactionChannel.POS);
        deleted.setIsEmi(true);
        deleted.setConvenienceFee(new BigDecimal("15.00"));

        Category cat = new Category("Food", currentUser);
        cat.setId(UUID.randomUUID());
        deleted.setCategories(Set.of(cat));

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        MergeTransactionsResponse response = transactionService.mergeTransactions(keepId, deleteId);

        assertEquals(keepId, response.keptId());
        assertEquals("Deleted user note", kept.getDescription());
        assertEquals(TransactionChannel.POS, kept.getChannel());
        assertTrue(kept.getIsEmi());
        assertEquals(new BigDecimal("15.00"), kept.getConvenienceFee());
        assertEquals(1, kept.getCategories().size());
        assertEquals(ReviewType.MANUALLY_REVIEWED, response.reviewType());
        assertTrue(response.remainingReasons().isEmpty());
        verify(transactionRepository).delete(deleted);
    }

    @Test
    void testMerge_CategoriesNotUnioned() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        Category keptCat = new Category("Shopping", currentUser);
        keptCat.setId(UUID.randomUUID());
        kept.setCategories(Set.of(keptCat));

        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);
        Category deletedCat = new Category("Food", currentUser);
        deletedCat.setId(UUID.randomUUID());
        deleted.setCategories(Set.of(deletedCat));

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.mergeTransactions(keepId, deleteId);

        assertEquals(1, kept.getCategories().size());
        assertEquals(keptCat.getId(), kept.getCategories().iterator().next().getCategory().getId());
    }

    @Test
    void testMerge_PartialReasonClearing() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        kept.getReviewReasons().add(ReviewReason.CATEGORY_UNVERIFIED);

        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        MergeTransactionsResponse response = transactionService.mergeTransactions(keepId, deleteId);

        assertEquals(ReviewType.NEEDS_REVIEW, response.reviewType());
        assertEquals(Set.of(ReviewReason.CATEGORY_UNVERIFIED), response.remainingReasons());
    }

    private TransactionLink createLink(LinkType type, TransactionLinkMember... members) {
        TransactionLink link = new TransactionLink();
        link.setId(UUID.randomUUID());
        link.setType(type);
        for (TransactionLinkMember m : members) {
            link.getMembers().add(m);
        }
        return link;
    }

    @Test
    void testMerge_LinkRepointedToKeptPreservingAnchor() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);
        Transaction third = createTestTxn(UUID.randomUUID(), testAccount, TransactionSource.manual);

        TransactionLink link = createLink(LinkType.REFUND);
        link.getMembers().add(new TransactionLinkMember(link, deleted, true));
        link.getMembers().add(new TransactionLinkMember(link, third, false));

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(List.of(deleteId)))
                .thenReturn(List.of(link.getId()));
        when(transactionLinkRepository.findWithMembersByIdIn(List.of(link.getId())))
                .thenReturn(List.of(link));
        when(transactionLinkRepository.findByMembers_Transaction_Id(keepId)).thenReturn(Optional.empty());
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.mergeTransactions(keepId, deleteId);

        assertEquals(2, link.getMembers().size());
        assertTrue(link.getMembers().stream()
                .anyMatch(m -> m.getTransaction().getId().equals(keepId) && m.isAnchor()));
        assertTrue(link.getMembers().stream()
                .noneMatch(m -> m.getTransaction().getId().equals(deleteId)));
        verify(transactionLinkRepository).save(link);
        verify(transactionLinkRepository, never()).delete(any(TransactionLink.class));
    }

    @Test
    void testMerge_SharedLinkBetweenTwinsDissolved() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);

        TransactionLink link = createLink(LinkType.TRANSFER);
        link.getMembers().add(new TransactionLinkMember(link, kept, true));
        link.getMembers().add(new TransactionLinkMember(link, deleted, false));

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(List.of(deleteId)))
                .thenReturn(List.of(link.getId()));
        when(transactionLinkRepository.findWithMembersByIdIn(List.of(link.getId())))
                .thenReturn(List.of(link));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.mergeTransactions(keepId, deleteId);

        verify(transactionLinkRepository).delete(link);
        verify(transactionLinkRepository, never()).findByMembers_Transaction_Id(any());
    }

    @Test
    void testMerge_KeptAlreadyLinkedElsewhere_DeletedMemberDropped() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);
        Transaction third = createTestTxn(UUID.randomUUID(), testAccount, TransactionSource.manual);

        TransactionLink deletedLink = createLink(LinkType.REFUND);
        deletedLink.getMembers().add(new TransactionLinkMember(deletedLink, deleted, true));
        deletedLink.getMembers().add(new TransactionLinkMember(deletedLink, third, false));

        TransactionLink keptOwnLink = createLink(LinkType.TRANSFER);

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(transactionLinkRepository.findLinkIdsByMemberTransactionIds(List.of(deleteId)))
                .thenReturn(List.of(deletedLink.getId()));
        when(transactionLinkRepository.findWithMembersByIdIn(List.of(deletedLink.getId())))
                .thenReturn(List.of(deletedLink));
        when(transactionLinkRepository.findByMembers_Transaction_Id(keepId))
                .thenReturn(Optional.of(keptOwnLink));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.mergeTransactions(keepId, deleteId);

        // The deleted twin's link loses its anchor and dissolves; it is never re-pointed to kept.
        verify(transactionLinkRepository).delete(deletedLink);
        assertTrue(deletedLink.getMembers().stream()
                .noneMatch(m -> m.getTransaction().getId().equals(keepId)));
    }

    @Test
    void testMerge_AlreadyReviewedKeptNotRestamped() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        kept.setReviewType(ReviewType.NA);
        kept.setReviewReasons(new HashSet<>());

        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        MergeTransactionsResponse response = transactionService.mergeTransactions(keepId, deleteId);

        assertEquals(ReviewType.NA, response.reviewType());
        assertNull(kept.getReviewedAt());
    }

    @Test
    void testMerge_StatementTransactionRepointing() {
        UUID keepId = UUID.randomUUID();
        UUID deleteId = UUID.randomUUID();
        UUID stmtId = UUID.randomUUID();

        Transaction kept = createTestTxn(keepId, testAccount, TransactionSource.gmail_statement);
        Transaction deleted = createTestTxn(deleteId, testAccount, TransactionSource.gmail_transaction_alert);

        StatementTransaction st = new StatementTransaction(stmtId, deleteId, 1, new BigDecimal("500.00"), true);

        when(transactionRepository.findById(keepId)).thenReturn(Optional.of(kept));
        when(transactionRepository.findById(deleteId)).thenReturn(Optional.of(deleted));
        when(statementTransactionRepository.findByIdTransactionId(deleteId)).thenReturn(List.of(st));
        when(statementTransactionRepository.existsById(any())).thenReturn(false);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.mergeTransactions(keepId, deleteId);

        verify(statementTransactionRepository).delete(st);
        verify(statementTransactionRepository).save(any(StatementTransaction.class));
    }
}
