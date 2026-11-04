package com.financeos.domain.statement;

import com.financeos.core.observability.AuditLogger;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.notification.feed.AppNotificationRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.StatementDuplicateReconciler;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.TransactionsIngestedEvent;
import com.financeos.domain.transaction.link.LinkType;
import com.financeos.domain.transaction.link.TransactionLink;
import com.financeos.domain.transaction.link.TransactionLinkMember;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.transaction.link.TransactionLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StatementMoveServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID statementId = UUID.randomUUID();
    private AccountRepository accountRepository;
    private StatementRepository statementRepository;
    private StatementTransactionRepository stRepo;
    private TransactionRepository txnRepo;
    private TransactionLinkRepository linkRepo;
    private TransactionLinkService linkService;
    private TransactionMatcher matcher;
    private StatementDuplicateReconciler reconciler;
    private CardRepository cardRepository;
    private AppNotificationRepository notifications;
    private GmailProcessedMessageRepository gmailMessages;
    private ApplicationEventPublisher events;
    private StatementMoveService service;

    private Account source;
    private Account target;
    private Statement statement;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        statementRepository = mock(StatementRepository.class);
        stRepo = mock(StatementTransactionRepository.class);
        txnRepo = mock(TransactionRepository.class);
        linkRepo = mock(TransactionLinkRepository.class);
        linkService = mock(TransactionLinkService.class);
        matcher = mock(TransactionMatcher.class);
        reconciler = mock(StatementDuplicateReconciler.class);
        cardRepository = mock(CardRepository.class);
        gmailMessages = mock(GmailProcessedMessageRepository.class);
        notifications = mock(AppNotificationRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new StatementMoveService(accountRepository, statementRepository, stRepo, txnRepo, linkRepo, linkService, matcher,
                new ReviewStatusManager(), reconciler, cardRepository, notifications, gmailMessages, events, mock(AuditLogger.class));

        source = account(AccountType.credit_card);
        target = account(AccountType.credit_card);
        statement = new Statement();
        statement.setId(statementId);
        statement.setAccount(source);
        statement.setPeriodStart(LocalDate.of(2026, 1, 1));
        statement.setPeriodEnd(LocalDate.of(2026, 1, 31));
        when(accountRepository.findByIdAndUser_Id(source.getId(), userId)).thenReturn(Optional.of(source));
        when(accountRepository.findByIdAndUser_Id(target.getId(), userId)).thenReturn(Optional.of(target));
        when(statementRepository.findByIdAndAccount_Id(statementId, source.getId())).thenReturn(Optional.of(statement));
        when(reconciler.pair(anyList(), anyList(), any(), any(), any())).thenReturn(List.of());
    }

    private static Account account(AccountType type) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setType(type);
        return a;
    }

    private Transaction txn(Account account, int day) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setAccount(account);
        t.setDate(LocalDate.of(2026, 1, day));
        t.setAmount(BigDecimal.TEN);
        t.setType(TransactionType.DEBIT);
        t.setSource(TransactionSource.file_upload);
        t.setReviewReasons(new HashSet<>());
        return t;
    }

    private void linked(Transaction... rows) {
        List<UUID> ids = java.util.Arrays.stream(rows).map(Transaction::getId).toList();
        when(stRepo.findTransactionIdsByStatementId(statementId)).thenReturn(ids);
        when(txnRepo.findAllByIdInAndUserId(anyList(), eq(userId))).thenReturn(List.of(rows));
        when(stRepo.findLinkedToOtherStatements(anyCollection(), eq(statementId))).thenReturn(Set.of());
    }

    @Test
    void movesTheStatementAndItsRowsKeepingTheirEdits() {
        Transaction a = txn(source, 5);
        a.setCategoryKey("food");
        Transaction b = txn(source, 6);
        linked(a, b);

        StatementMoveService.Moved r = service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(r.moved()).isEqualTo(2);
        assertThat(a.getAccount()).isSameAs(target);
        assertThat(b.getAccount()).isSameAs(target);
        assertThat(a.getCategoryKey()).isEqualTo("food");
        assertThat(statement.getAccount()).isSameAs(target);
        verify(statementRepository).save(statement);
        verify(txnRepo, atLeastOnce()).saveAll(anyList());
        verify(notifications).moveStatementRows(userId, statementId, target.getId());
        verify(events).publishEvent(any(TransactionsIngestedEvent.class));
    }

    @Test
    void aRowAnotherStatementAlsoHasALineForStaysBehind() {
        Transaction mine = txn(source, 5);
        Transaction shared = txn(source, 6);
        linked(mine, shared);
        when(stRepo.findLinkedToOtherStatements(anyCollection(), eq(statementId))).thenReturn(Set.of(shared.getId()));

        assertThat(service.move(userId, source.getId(), target.getId(), statementId).moved()).isEqualTo(1);

        assertThat(mine.getAccount()).isSameAs(target);
        assertThat(shared.getAccount()).isSameAs(source);
    }

    @Test
    void aRowOnAnotherAccountIsNotMoved() {
        Transaction elsewhere = txn(account(AccountType.credit_card), 5);
        linked(elsewhere);

        assertThat(service.move(userId, source.getId(), target.getId(), statementId).moved()).isZero();
        verify(events, never()).publishEvent(any(TransactionsIngestedEvent.class));
    }

    @Test
    void cardIsMappedToTheTargetCardWithTheSameLast4ElseCleared() {
        Card oldCard = new Card();
        oldCard.setAccount(source);
        oldCard.setLast4("1111");
        Card other = new Card();
        other.setAccount(source);
        other.setLast4("2222");
        Card newCard = new Card();
        newCard.setAccount(target);
        newCard.setLast4("1111");
        when(cardRepository.findOpenByAccountId(target.getId())).thenReturn(List.of(newCard));
        Transaction a = txn(source, 5);
        a.setCard(oldCard);
        Transaction b = txn(source, 6);
        b.setCard(other);
        linked(a, b);

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(a.getCard()).isSameAs(newCard);
        assertThat(b.getCard()).isNull();
    }

    @Test
    void aDuplicateFlagBetweenAMovedRowAndARowLeftInTheSourceIsCleared() {
        Transaction moved = txn(source, 5);
        Transaction stays = txn(source, 5);
        new ReviewStatusManager().addReason(moved, ReviewReason.DUPLICATE_SUSPECT);
        new ReviewStatusManager().addReason(stays, ReviewReason.DUPLICATE_SUSPECT);
        linked(moved);
        when(txnRepo.findByAccountIdAndDateRange(eq(source.getId()), any(), any())).thenReturn(List.of(moved, stays));
        when(matcher.areDuplicates(any(), any(), eq(0))).thenAnswer(inv -> inv.getArgument(0) != inv.getArgument(1));

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(stays.getReviewReasons()).doesNotContain(ReviewReason.DUPLICATE_SUSPECT);
        assertThat(stays.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
        assertThat(moved.getReviewReasons()).doesNotContain(ReviewReason.DUPLICATE_SUSPECT);
    }

    @Test
    void aMovedRowThatLooksLikeATargetRowIsFlaggedBothWays() {
        Transaction moved = txn(source, 5);
        Transaction inTarget = txn(target, 5);
        linked(moved);
        when(txnRepo.findByAccountIdAndDateRange(eq(target.getId()), any(), any())).thenReturn(List.of(moved, inTarget));
        when(matcher.areDuplicates(moved, inTarget, 0)).thenReturn(true);

        StatementMoveService.Moved r = service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(r.duplicatesFlagged()).isEqualTo(1);
        assertThat(moved.getReviewReasons()).contains(ReviewReason.DUPLICATE_SUSPECT);
        assertThat(inTarget.getReviewReasons()).contains(ReviewReason.DUPLICATE_SUSPECT);
    }

    @Test
    void certainAlertPairsOnTheTargetAreMergedAfterCommit() {
        Transaction moved = txn(source, 5);
        Transaction alert = txn(target, 5);
        alert.setSource(TransactionSource.gmail_transaction_alert);
        linked(moved);
        when(txnRepo.findByAccountIdAndDateRange(eq(target.getId()), any(), any())).thenReturn(List.of(moved, alert));
        StatementDuplicateReconciler.Pair pair = new StatementDuplicateReconciler.Pair(moved, alert, true);
        when(reconciler.pair(anyList(), anyList(), any(), any(), any())).thenReturn(List.of(pair));

        service.move(userId, source.getId(), target.getId(), statementId);

        verify(reconciler).mergeCertainAfterCommit(List.of(pair));
    }

    private TransactionLink link(LinkType type, Transaction anchor, Transaction other) {
        TransactionLink l = new TransactionLink();
        l.setId(UUID.randomUUID());
        l.setType(type);
        l.getMembers().add(new TransactionLinkMember(l, anchor, true));
        l.getMembers().add(new TransactionLinkMember(l, other, false));
        return l;
    }

    @Test
    void aTransferWhoseBothEndsNowSitInTheTargetIsUnlinkedOthersStay() {
        Transaction a = txn(source, 5);
        Transaction b = txn(target, 5);
        Transaction c = txn(source, 7);
        Transaction d = txn(account(AccountType.bank_account), 7);
        linked(a, c);
        TransactionLink self = link(LinkType.TRANSFER, a, b);
        TransactionLink cross = link(LinkType.TRANSFER, c, d);
        TransactionLink refund = link(LinkType.REFUND, txn(source, 8), b);
        when(linkRepo.findDistinctByMembers_Transaction_IdIn(anyCollection())).thenReturn(List.of(self, cross, refund));

        service.move(userId, source.getId(), target.getId(), statementId);

        verify(linkService).deleteLink(self.getId());
        verify(linkService, never()).deleteLink(cross.getId());
        verify(linkService, never()).deleteLink(refund.getId());
    }

    @Test
    void sourceAndTargetLastStatementDatesAreRefreshed() {
        linked(txn(source, 5));
        LocalDate previous = LocalDate.of(2025, 12, 31);
        when(statementRepository.findLatestPeriodEnd(source.getId())).thenReturn(previous);

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(source.getLastStatementDate()).isEqualTo(previous);
        assertThat(target.getLastStatementDate()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    void aReversalSplitAcrossAccountsIsUnlinkedButOneWhollyMovedStays() {
        Transaction a = txn(source, 5);
        Transaction b = txn(source, 6);
        Transaction c = txn(source, 7);
        Transaction leftBehind = txn(source, 8);
        linked(a, b, c);
        TransactionLink whole = link(LinkType.REVERSAL, a, b);
        TransactionLink split = link(LinkType.REVERSAL, c, leftBehind);
        when(linkRepo.findDistinctByMembers_Transaction_IdIn(anyCollection())).thenReturn(List.of(whole, split));
        // the statement only has lines a, b, c; leftBehind stays on the source
        service.move(userId, source.getId(), target.getId(), statementId);

        verify(linkService).deleteLink(split.getId());
        verify(linkService, never()).deleteLink(whole.getId());
    }

    @Test
    void anAlertRowWhoseOnlyPartnerWasAMovedRowWithinTwoDaysLosesItsFlag() {
        Transaction moved = txn(source, 5);
        Transaction alert = txn(source, 7);
        alert.setSource(TransactionSource.gmail_transaction_alert);
        new ReviewStatusManager().addReason(alert, ReviewReason.DUPLICATE_SUSPECT);
        linked(moved);
        when(txnRepo.findByAccountIdAndDateRange(eq(source.getId()), any(), any())).thenReturn(List.of(moved, alert));

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(alert.getReviewReasons()).doesNotContain(ReviewReason.DUPLICATE_SUSPECT);
    }

    @Test
    void aStayingRowWithAnAlertPartnerWithinTwoDaysKeepsItsFlag() {
        Transaction moved = txn(source, 5);
        Transaction stays = txn(source, 5);
        Transaction alert = txn(source, 6);
        alert.setSource(TransactionSource.gmail_transaction_alert);
        new ReviewStatusManager().addReason(stays, ReviewReason.DUPLICATE_SUSPECT);
        linked(moved);
        when(txnRepo.findByAccountIdAndDateRange(eq(source.getId()), any(), any())).thenReturn(List.of(moved, stays, alert));
        when(matcher.areDuplicates(any(), any(), eq(0))).thenAnswer(inv -> {
            Transaction x = inv.getArgument(0);
            Transaction y = inv.getArgument(1);
            return x != y && x.getDate().equals(y.getDate());
        });

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(stays.getReviewReasons()).contains(ReviewReason.DUPLICATE_SUSPECT);
    }

    @Test
    void theGmailLedgerFollowsAGmailStatementAndItsAlertRows() {
        statement.setSource(StatementSource.gmail);
        statement.setSourceRef("msg-1");
        Transaction row = txn(source, 5);
        row.setSource(TransactionSource.gmail_statement);
        row.setSourceMessageId("msg-1:0");
        Transaction alert = txn(source, 6);
        alert.setSource(TransactionSource.gmail_transaction_alert);
        alert.setSourceMessageId("msg-2");
        linked(row, alert);

        service.move(userId, source.getId(), target.getId(), statementId);

        verify(gmailMessages).moveToAccount(eq(userId), eq(source.getId()), eq(target),
                argThat(ids -> ids.size() == 2 && ids.contains("msg-1") && ids.contains("msg-2")));
    }

    @Test
    void theTargetLastStatementDateFallsBackToTheLatestLineWhenThePeriodEndIsMissing() {
        statement.setPeriodEnd(null);
        linked(txn(source, 5), txn(source, 9));

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(target.getLastStatementDate()).isEqualTo(LocalDate.of(2026, 1, 9));
    }
}
