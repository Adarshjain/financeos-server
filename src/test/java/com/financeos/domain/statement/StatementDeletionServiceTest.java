package com.financeos.domain.statement;

import com.financeos.api.statement.dto.StatementDeletionPreviewResponse;
import com.financeos.api.statement.dto.StatementDeletionResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.observability.AuditLogger;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.inbox.InboxItemState;
import com.financeos.domain.inbox.InboxItemStateRepository;
import com.financeos.domain.obligation.ObligationRefService;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionService;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StatementDeletionServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 2, 25);

    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private StatementTransactionRepository statementTransactionRepository;
    private TransactionRepository transactionRepository;
    private TransactionService transactionService;
    private TransactionLinkRepository transactionLinkRepository;
    private ObligationRefService obligationRefService;
    private InboxItemStateRepository inboxItemStateRepository;
    private StatementDeletionService service;

    private UUID userId;
    private User user;
    private Account account;
    private Statement statement;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        statementTransactionRepository = mock(StatementTransactionRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionService = mock(TransactionService.class);
        transactionLinkRepository = mock(TransactionLinkRepository.class);
        obligationRefService = mock(ObligationRefService.class);
        inboxItemStateRepository = mock(InboxItemStateRepository.class);
        service = new StatementDeletionService(accountRepository, statementRepository, statementTransactionRepository,
                transactionRepository, transactionService, transactionLinkRepository, obligationRefService,
                new TransactionMatcher(), new ReviewStatusManager(), inboxItemStateRepository, mock(AuditLogger.class));

        userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);
        user = new User();
        user.setId(userId);
        account = new Account();
        account.setId(UUID.randomUUID());
        account.setUser(user);
        account.setLastStatementDate(LocalDate.of(2026, 2, 27));
        statement = new Statement();
        statement.setId(UUID.randomUUID());
        statement.setAccount(account);
        statement.setUser(user);
        statement.setSource(StatementSource.file_upload);

        when(accountRepository.findByIdAndUser_Id(account.getId(), userId)).thenReturn(Optional.of(account));
        when(statementRepository.findByIdAndAccount_Id(statement.getId(), account.getId())).thenReturn(Optional.of(statement));
        when(statementTransactionRepository.findLinkedToOtherStatements(anyCollection(), eq(statement.getId()))).thenReturn(Set.of());
        when(inboxItemStateRepository.findByUserIdAndItemKey(any(), any())).thenReturn(Optional.empty());
        when(obligationRefService.refsFor(anyCollection())).thenReturn(Map.of());
        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection())).thenReturn(List.of());
        when(transactionRepository.findByAccountIdAndDateRange(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ------------------------------------------------------------ what goes, what stays

    @Test
    void fileUploadStatementDeletesTheRowsItCreatedAndKeepsWhatItOnlyMatched() {
        Transaction created1 = txn(TransactionSource.file_upload, null, "IMPS 605409990907", "1.00", TransactionType.CREDIT);
        Transaction created2 = txn(TransactionSource.file_upload, null, "IMPS 605409994425", "33000.00", TransactionType.CREDIT);
        Transaction manual = txn(TransactionSource.manual, null, "Rent", "5000.00", TransactionType.DEBIT);
        linked(created1, created2, manual);

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(deletedIds()).containsExactlyInAnyOrder(created1.getId(), created2.getId());
        assertThat(res.transactionsDeleted()).isEqualTo(2);
        assertThat(res.transactionsKept()).isEqualTo(1);
        assertThat(res.alertsReopened()).isZero();
        verify(statementTransactionRepository).deleteByStatementId(statement.getId());
        verify(statementRepository).delete(statement);
    }

    @Test
    void gmailStatementDeletesOnlyRowsKeyedByItsOwnMessage() {
        statement.setSource(StatementSource.gmail);
        statement.setSourceRef("msg-1");
        Transaction own = txn(TransactionSource.gmail_statement, "msg-1:0", "UPI CRED", "100.00", TransactionType.DEBIT);
        Transaction prefixLookAlike = txn(TransactionSource.gmail_statement, "msg-10:0", "UPI CRED", "200.00", TransactionType.DEBIT);
        Transaction otherMessage = txn(TransactionSource.gmail_statement, "msg-2:3", "NEFT", "300.00", TransactionType.CREDIT);
        Transaction fileRow = txn(TransactionSource.file_upload, null, "IMPS", "400.00", TransactionType.CREDIT);
        linked(own, prefixLookAlike, otherMessage, fileRow);

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(deletedIds()).containsExactly(own.getId());
        assertThat(res.transactionsKept()).isEqualTo(3);
    }

    @Test
    void rowAnotherStatementAlsoCoversIsKept() {
        Transaction shared = txn(TransactionSource.file_upload, null, "IMPS", "1.00", TransactionType.CREDIT);
        Transaction own = txn(TransactionSource.file_upload, null, "NEFT", "2.00", TransactionType.CREDIT);
        linked(shared, own);
        when(statementTransactionRepository.findLinkedToOtherStatements(anyCollection(), eq(statement.getId())))
                .thenReturn(Set.of(shared.getId()));

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(deletedIds()).containsExactly(own.getId());
        assertThat(res.transactionsKept()).isEqualTo(1);
    }

    @Test
    void linkedRowOfAnotherAccountIsLeftAlone() {
        Transaction own = txn(TransactionSource.file_upload, null, "IMPS", "1.00", TransactionType.CREDIT);
        Transaction foreign = txn(TransactionSource.file_upload, null, "IMPS", "2.00", TransactionType.CREDIT);
        Account other = new Account();
        other.setId(UUID.randomUUID());
        foreign.setAccount(other);
        linked(own, foreign);

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(deletedIds()).containsExactly(own.getId());
        assertThat(res.transactionsKept()).isZero();
    }

    @Test
    void statementWithNoLinkedRowsDeletesOnlyTheStatement() {
        linked();

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        verify(transactionService, never()).batchDelete(anyList());
        verify(statementRepository).delete(statement);
        assertThat(res).isEqualTo(new StatementDeletionResponse(0, 0, 0, 0));
    }

    @Test
    void deletesInChunksTheDatabaseInListAccepts() {
        List<Transaction> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(txn(TransactionSource.file_upload, null, "ROW " + i, "1.00", TransactionType.DEBIT));
        }
        linked(rows.toArray(new Transaction[0]));

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionService, times(2)).batchDelete(captor.capture());
        assertThat(captor.getAllValues().get(0)).hasSize(500);
        assertThat(captor.getAllValues().get(1)).hasSize(1);
        verify(transactionRepository, times(2)).findAllByIdInAndUserId(anyList(), eq(userId));
        assertThat(res.transactionsDeleted()).isEqualTo(501);
    }

    // ------------------------------------------------------------ email alerts

    @Test
    void alertThisStatementReconciledGoesBackToReviewAsUnreconciled() {
        statement.setSource(StatementSource.gmail);
        statement.setSourceRef("msg-1");
        Transaction alert = txn(TransactionSource.gmail_transaction_alert, "alert-9", "Swiggy", "450.00", TransactionType.DEBIT);
        alert.setReviewType(ReviewType.AUTO_REVIEWED);
        linked(alert);

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(alert.getReviewType()).isEqualTo(ReviewType.NEEDS_REVIEW);
        assertThat(alert.getReviewReasons()).containsExactly(ReviewReason.UNRECONCILED);
        verify(transactionRepository).saveAll(List.of(alert));
        assertThat(res.alertsReopened()).isEqualTo(1);
        assertThat(deletedIds()).isEmpty();
    }

    @Test
    void alertAnotherStatementStillBacksOrAlreadyUnreconciledIsNotReopened() {
        statement.setSource(StatementSource.gmail);
        statement.setSourceRef("msg-1");
        Transaction backedElsewhere = txn(TransactionSource.gmail_transaction_alert, "alert-1", "Swiggy", "450.00", TransactionType.DEBIT);
        backedElsewhere.setReviewType(ReviewType.AUTO_REVIEWED);
        Transaction alreadyOpen = txn(TransactionSource.gmail_transaction_alert, "alert-2", "Zomato", "300.00", TransactionType.DEBIT);
        new ReviewStatusManager().addReason(alreadyOpen, ReviewReason.UNRECONCILED);
        linked(backedElsewhere, alreadyOpen);
        when(statementTransactionRepository.findLinkedToOtherStatements(anyCollection(), eq(statement.getId())))
                .thenReturn(Set.of(backedElsewhere.getId()));

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(backedElsewhere.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
        assertThat(alreadyOpen.getReviewReasons()).containsExactly(ReviewReason.UNRECONCILED);
        assertThat(res.alertsReopened()).isZero();
        assertThat(res.transactionsKept()).isEqualTo(2);
    }

    // ------------------------------------------------------------ duplicate flags

    @Test
    void duplicateFlagWhoseOnlyLookAlikeWasDeletedIsCleared() {
        Transaction created = txn(TransactionSource.file_upload, null, "UPI CRED CLUB", "100.00", TransactionType.DEBIT);
        Transaction survivor = txn(TransactionSource.gmail_statement, "msg-7:0", "UPI CRED CLUB", "100.00", TransactionType.DEBIT);
        new ReviewStatusManager().addReason(survivor, ReviewReason.DUPLICATE_SUSPECT);
        linked(created);
        when(transactionRepository.findByAccountIdAndDateRange(account.getId(), DAY, DAY))
                .thenReturn(List.of(created, survivor));

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(survivor.getReviewReasons()).isEmpty();
        assertThat(survivor.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
        assertThat(res.duplicateFlagsCleared()).isEqualTo(1);
    }

    @Test
    void duplicateFlagStaysWhileAnotherLookAlikeRemains() {
        Transaction created = txn(TransactionSource.file_upload, null, "UPI CRED CLUB", "100.00", TransactionType.DEBIT);
        Transaction survivor = txn(TransactionSource.gmail_statement, "msg-7:0", "UPI CRED CLUB", "100.00", TransactionType.DEBIT);
        Transaction otherLookAlike = txn(TransactionSource.file_upload, null, "UPI CRED CLUB", "100.00", TransactionType.DEBIT);
        new ReviewStatusManager().addReason(survivor, ReviewReason.DUPLICATE_SUSPECT);
        linked(created);
        when(transactionRepository.findByAccountIdAndDateRange(account.getId(), DAY, DAY))
                .thenReturn(List.of(created, survivor, otherLookAlike));

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(survivor.getReviewReasons()).containsExactly(ReviewReason.DUPLICATE_SUSPECT);
        assertThat(res.duplicateFlagsCleared()).isZero();
    }

    @Test
    void duplicateFlagRaisedForAnUnrelatedRowIsUntouched() {
        Transaction created = txn(TransactionSource.file_upload, null, "UPI CRED CLUB", "100.00", TransactionType.DEBIT);
        Transaction unrelated = txn(TransactionSource.manual, null, "Swiggy", "999.00", TransactionType.DEBIT);
        new ReviewStatusManager().addReason(unrelated, ReviewReason.DUPLICATE_SUSPECT);
        linked(created);
        when(transactionRepository.findByAccountIdAndDateRange(account.getId(), DAY, DAY))
                .thenReturn(List.of(created, unrelated));

        StatementDeletionResponse res = service.delete(account.getId(), statement.getId());

        assertThat(unrelated.getReviewReasons()).containsExactly(ReviewReason.DUPLICATE_SUSPECT);
        assertThat(res.duplicateFlagsCleared()).isZero();
    }

    // ------------------------------------------------------------ account state and inbox

    @Test
    void lastStatementDateFallsBackToTheLatestRemainingStatement() {
        linked();
        when(statementRepository.findLatestPeriodEnd(account.getId())).thenReturn(LocalDate.of(2026, 1, 27));

        service.delete(account.getId(), statement.getId());

        assertThat(account.getLastStatementDate()).isEqualTo(LocalDate.of(2026, 1, 27));
        verify(accountRepository).save(account);
    }

    @Test
    void lastStatementDateClearsWhenNoStatementRemains() {
        linked();
        when(statementRepository.findLatestPeriodEnd(account.getId())).thenReturn(null);

        service.delete(account.getId(), statement.getId());

        assertThat(account.getLastStatementDate()).isNull();
    }

    @Test
    void inboxStateOfTheStatementsBillIsDropped() {
        linked();
        InboxItemState state = new InboxItemState();
        when(inboxItemStateRepository.findByUserIdAndItemKey(userId, "bill:" + statement.getId())).thenReturn(Optional.of(state));

        service.delete(account.getId(), statement.getId());

        verify(inboxItemStateRepository).delete(state);
    }

    // ------------------------------------------------------------ preview

    @Test
    void previewCountsWithoutChangingAnything() {
        statement.setSource(StatementSource.gmail);
        statement.setSourceRef("msg-1");
        Transaction lendingLinked = txn(TransactionSource.gmail_statement, "msg-1:0", "IMPS RAVI", "5000.00", TransactionType.DEBIT);
        Transaction transferLinked = txn(TransactionSource.gmail_statement, "msg-1:1", "NEFT SELF", "7000.00", TransactionType.DEBIT);
        Transaction plain = txn(TransactionSource.gmail_statement, "msg-1:2", "UPI", "10.00", TransactionType.DEBIT);
        Transaction alert = txn(TransactionSource.gmail_transaction_alert, "alert-1", "Swiggy", "450.00", TransactionType.DEBIT);
        alert.setReviewType(ReviewType.AUTO_REVIEWED);
        Transaction keptPartner = txn(TransactionSource.manual, null, "Self transfer in", "7000.00", TransactionType.CREDIT);
        linked(lendingLinked, transferLinked, plain, alert);
        when(obligationRefService.refsFor(anyCollection())).thenReturn(Map.of(lendingLinked.getId(), List.of()));
        TransactionLink link = new TransactionLink();
        link.setMembers(new HashSet<>(List.of(new TransactionLinkMember(link, transferLinked, true),
                new TransactionLinkMember(link, keptPartner, false))));
        when(transactionLinkRepository.findDistinctByMembers_Transaction_IdIn(anyCollection())).thenReturn(List.of(link));

        StatementDeletionPreviewResponse preview = service.preview(account.getId(), statement.getId());

        assertThat(preview).isEqualTo(new StatementDeletionPreviewResponse(3, 1, 2, 1));
        assertThat(alert.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
        verify(transactionService, never()).batchDelete(anyList());
        verify(statementRepository, never()).delete(any());
        verify(transactionRepository, never()).saveAll(any());
    }

    // ------------------------------------------------------------ tenancy

    @Test
    void anotherUsersAccountIsNotFoundAndNothingIsTouched() {
        UUID foreignAccount = UUID.randomUUID();
        when(accountRepository.findByIdAndUser_Id(foreignAccount, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(foreignAccount, statement.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.preview(foreignAccount, statement.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoMutation();
    }

    @Test
    void statementOfAnotherAccountIsNotFoundAndNothingIsTouched() {
        UUID statementElsewhere = UUID.randomUUID();
        when(statementRepository.findByIdAndAccount_Id(statementElsewhere, account.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(account.getId(), statementElsewhere))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoMutation();
    }

    // ------------------------------------------------------------ helpers

    private void verifyNoMutation() {
        verify(transactionService, never()).batchDelete(anyList());
        verify(statementRepository, never()).delete(any());
        verify(statementTransactionRepository, never()).deleteByStatementId(any());
        verify(accountRepository, never()).save(any());
    }

    private Transaction txn(TransactionSource source, String sourceMessageId, String description, String amount,
                            TransactionType type) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setUser(user);
        t.setAccount(account);
        t.setDate(DAY);
        t.setAmount(new BigDecimal(amount));
        t.setType(type);
        t.setSource(source);
        t.setSourceMessageId(sourceMessageId);
        t.setSourcedDescription(description);
        t.setReviewType(ReviewType.AUTO_REVIEWED);
        return t;
    }

    private void linked(Transaction... txns) {
        List<UUID> ids = new ArrayList<>();
        for (Transaction t : txns) {
            ids.add(t.getId());
        }
        when(statementTransactionRepository.findTransactionIdsByStatementId(statement.getId())).thenReturn(ids);
        when(transactionRepository.findAllByIdInAndUserId(anyList(), eq(userId))).thenAnswer(inv -> {
            Collection<UUID> wanted = inv.getArgument(0);
            List<Transaction> out = new ArrayList<>();
            for (Transaction t : txns) {
                if (wanted.contains(t.getId())) {
                    out.add(t);
                }
            }
            return out;
        });
    }

    private List<UUID> deletedIds() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionService, atLeast(0)).batchDelete(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }
}
