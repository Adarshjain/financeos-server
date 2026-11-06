package com.financeos.domain.statement;

import com.financeos.core.observability.AuditLogger;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.Card;
import com.financeos.domain.account.card.CardRepository;
import com.financeos.domain.notification.feed.AppNotificationRepository;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.domain.categorization.CategorizationService;
import com.financeos.domain.category.TagRepository;
import com.financeos.domain.transaction.CategorySource;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionMatcher;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.TransactionService;
import com.financeos.domain.user.UserRepository;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.TransactionsIngestedEvent;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
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
import java.util.Map;
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
    private StatementBatchMatcher batchMatcher;
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
        batchMatcher = mock(StatementBatchMatcher.class);
        cardRepository = mock(CardRepository.class);
        gmailMessages = mock(GmailProcessedMessageRepository.class);
        notifications = mock(AppNotificationRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new StatementMoveService(accountRepository, statementRepository, stRepo, txnRepo, linkRepo, linkService, matcher,
                new ReviewStatusManager(), batchMatcher, cardRepository, notifications, gmailMessages, events, mock(AuditLogger.class));
        service.setTransactionService(new TransactionService(txnRepo, accountRepository, mock(TagRepository.class),
                mock(UserRepository.class), new ReviewStatusManager(), mock(CategorizationService.class), (TransactionService) null));
        when(txnRepo.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

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
    void theStatementIsMatchedAgainOnTheTargetAndNothingIsFlagged() {
        Transaction moved = txn(source, 5);
        Transaction inTarget = txn(target, 5);
        linked(moved);
        when(txnRepo.findByAccountIdAndDateRange(eq(target.getId()), any(), any())).thenReturn(List.of(moved, inTarget));
        when(matcher.areDuplicates(moved, inTarget, 0)).thenReturn(true);

        StatementMoveService.Moved r = service.move(userId, source.getId(), target.getId(), statementId);

        verify(batchMatcher).rerun(statement, target);
        assertThat(r.duplicatesFlagged()).isZero();
        assertThat(moved.getReviewReasons()).doesNotContain(ReviewReason.DUPLICATE_SUSPECT);
        assertThat(inTarget.getReviewReasons()).doesNotContain(ReviewReason.DUPLICATE_SUSPECT);
    }

    private void rerunReplaces(Transaction own, Transaction keep) {
        when(txnRepo.findById(own.getId())).thenReturn(Optional.of(own));
        when(txnRepo.findById(keep.getId())).thenReturn(Optional.of(keep));
        when(txnRepo.findAllById(org.mockito.ArgumentMatchers.anyIterable())).thenReturn(List.of(own, keep));
        when(batchMatcher.rerun(statement, target)).thenReturn(new StatementBatchMatcher.Result(
                1, 0, 0, 0, 0, List.of(), Map.of(own.getId(), keep.getId())));
    }

    @Test
    void anOwnRowWhoseLineNowSitsOnATargetAlertIsFoldedIntoItAndDeleted() {
        Transaction own = txn(source, 5);
        Transaction alert = txn(target, 5);
        alert.setSource(TransactionSource.gmail_transaction_alert);
        linked(own);
        rerunReplaces(own, alert);

        StatementMoveService.Moved r = service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(r.moved()).isEqualTo(1);
        verify(txnRepo).delete(own);
        verify(txnRepo, never()).delete(alert);
        // auto-linking runs only for rows that still exist: the one moved row was folded away
        verify(events, never()).publishEvent(any(TransactionsIngestedEvent.class));
    }

    @Test
    void foldingCarriesTheUsersChoicesTheTargetRowLacks() {
        Transaction own = txn(source, 5);
        own.setCategoryKey("travel");
        own.setSubcategoryKey("flights");
        own.setCategorySource(CategorySource.USER);
        own.setDescription("Goa trip");
        own.setTransactionExcluded(true);
        own.setExcludeSetByUser(true);
        Transaction alert = txn(target, 5);
        alert.setSource(TransactionSource.gmail_transaction_alert);
        alert.setCategoryKey("shopping");
        linked(own);
        rerunReplaces(own, alert);

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(alert.getCategoryKey()).isEqualTo("travel");
        assertThat(alert.getSubcategoryKey()).isEqualTo("flights");
        assertThat(alert.getDescription()).isEqualTo("Goa trip");
        assertThat(alert.isTransactionExcluded()).isTrue();
        assertThat(alert.isExcludeSetByUser()).isTrue();
    }

    @Test
    void theTargetRowKeepsItsOwnDescriptionAndCategoryWhenTheOwnRowsAreAutomatic() {
        Transaction own = txn(source, 5);
        own.setCategoryKey("shopping");
        own.setCategorySource(CategorySource.RULE);
        own.setDescription("mine");
        Transaction alert = txn(target, 5);
        alert.setSource(TransactionSource.gmail_transaction_alert);
        alert.setCategoryKey("food");
        alert.setDescription("typed on the alert");
        linked(own);
        rerunReplaces(own, alert);

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(alert.getCategoryKey()).isEqualTo("food");
        assertThat(alert.getDescription()).isEqualTo("typed on the alert");
    }

    @Test
    void anOwnRowThatCannotBeFoldedGetsItsLineBackAndTheTargetsAttachIsUndone() {
        Transaction own = txn(source, 5);
        own.setSourcedDescription("STATEMENT TEXT");
        Transaction alert = txn(target, 5);
        alert.setSource(TransactionSource.gmail_transaction_alert);
        alert.setDate(LocalDate.of(2026, 1, 4));
        alert.setSourcedDescription("ALERT TEXT");
        alert.setReviewReasons(new HashSet<>(Set.of(ReviewReason.NOT_ON_STATEMENT)));
        alert.setReviewType(ReviewType.NEEDS_REVIEW);
        UUID otherStatement = UUID.randomUUID();
        alert.setNotOnStatementId(otherStatement);
        StatementBatchMatcher.Prior before = new StatementBatchMatcher.Prior(alert.getDate(), alert.getSourcedDescription(),
                Set.copyOf(alert.getReviewReasons()), alert.getReviewType(), null, otherStatement);
        // the re-run's attach: statement wins on date and text, the tag goes, the line sits on the alert
        alert.setDate(LocalDate.of(2026, 1, 5));
        alert.setSourcedDescription("STATEMENT TEXT");
        alert.getReviewReasons().clear();
        alert.setReviewType(ReviewType.AUTO_REVIEWED);
        alert.setNotOnStatementId(null);
        StatementTransaction onAlert = new StatementTransaction(statementId, alert.getId(), 7, new BigDecimal("100"), true);
        when(stRepo.findByIdStatementId(statementId)).thenReturn(List.of(onAlert));
        linked(own);
        when(txnRepo.findById(own.getId())).thenReturn(Optional.of(own));
        when(txnRepo.findById(alert.getId())).thenReturn(Optional.of(alert));
        when(txnRepo.findAllById(org.mockito.ArgumentMatchers.anyIterable())).thenReturn(List.of(own, alert));
        when(batchMatcher.rerun(statement, target)).thenReturn(new StatementBatchMatcher.Result(
                1, 0, 0, 0, 0, List.of(), Map.of(own.getId(), alert.getId()), Map.of(alert.getId(), before)));
        TransactionService refusing = mock(TransactionService.class);
        when(refusing.mergeStatementDuplicate(alert.getId(), own.getId())).thenReturn(false);
        service.setTransactionService(refusing);

        StatementMoveService.Moved r = service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(r.moved()).isEqualTo(1);
        verify(txnRepo, never()).delete(any(Transaction.class));
        verify(stRepo).deleteAll(List.of(onAlert));
        verify(stRepo).saveAll(argThat((Iterable<StatementTransaction> links) -> {
            List<StatementTransaction> l = new java.util.ArrayList<>();
            links.forEach(l::add);
            return l.size() == 1 && l.get(0).getId().getTransactionId().equals(own.getId())
                    && l.get(0).getId().getStatementId().equals(statementId) && l.get(0).getLineIndex() == 7
                    && new BigDecimal("100").equals(l.get(0).getBalanceAfter());
        }));
        assertThat(alert.getDate()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(alert.getSourcedDescription()).isEqualTo("ALERT TEXT");
        assertThat(alert.getReviewReasons()).containsExactly(ReviewReason.NOT_ON_STATEMENT);
        assertThat(alert.getReviewType()).isEqualTo(ReviewType.NEEDS_REVIEW);
        assertThat(alert.getNotOnStatementId()).isEqualTo(otherStatement);
        verify(txnRepo).save(alert);
    }

    @Test
    void alertsTheMovedStatementTaggedOnTheSourceLoseTheTagBeforeTheRerun() {
        Transaction moved = txn(source, 5);
        linked(moved);
        Transaction tagged = txn(source, 10);
        tagged.setSource(TransactionSource.gmail_transaction_alert);
        new ReviewStatusManager().addReason(tagged, ReviewReason.NOT_ON_STATEMENT);
        tagged.setNotOnStatementId(statementId);
        Transaction alsoDuplicate = txn(source, 11);
        alsoDuplicate.setSource(TransactionSource.gmail_transaction_alert);
        new ReviewStatusManager().addReason(alsoDuplicate, ReviewReason.NOT_ON_STATEMENT);
        new ReviewStatusManager().addReason(alsoDuplicate, ReviewReason.DUPLICATE_SUSPECT);
        alsoDuplicate.setNotOnStatementId(statementId);
        when(txnRepo.findByNotOnStatementId(statementId)).thenReturn(List.of(tagged, alsoDuplicate));
        when(batchMatcher.rerun(statement, target)).thenAnswer(inv -> {
            // untagged before the re-run, which tags again on the target
            assertThat(tagged.getNotOnStatementId()).isNull();
            return new StatementBatchMatcher.Result(0, 0, 0, 0, 1, List.of());
        });

        service.move(userId, source.getId(), target.getId(), statementId);

        assertThat(tagged.getReviewReasons()).isEmpty();
        assertThat(tagged.getReviewType()).isEqualTo(ReviewType.AUTO_REVIEWED);
        assertThat(alsoDuplicate.getReviewReasons()).containsExactly(ReviewReason.DUPLICATE_SUSPECT);
        assertThat(alsoDuplicate.getReviewType()).isEqualTo(ReviewType.NEEDS_REVIEW);
        assertThat(alsoDuplicate.getNotOnStatementId()).isNull();
        verify(txnRepo).saveAll(List.of(tagged, alsoDuplicate));
        verify(batchMatcher).rerun(statement, target);
    }

    @Test
    void anOwnRowNothingOnTheTargetPairsWithIsKept() {
        Transaction own = txn(source, 5);
        linked(own);
        when(batchMatcher.rerun(statement, target)).thenReturn(new StatementBatchMatcher.Result(0, 0, 0, 0, 1, List.of()));

        assertThat(service.move(userId, source.getId(), target.getId(), statementId).moved()).isEqualTo(1);

        assertThat(own.getAccount()).isSameAs(target);
        verify(txnRepo, never()).delete(any(Transaction.class));
        verify(events).publishEvent(any(TransactionsIngestedEvent.class));
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
