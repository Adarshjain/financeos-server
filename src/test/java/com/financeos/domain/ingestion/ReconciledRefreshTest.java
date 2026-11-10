package com.financeos.domain.ingestion;

import com.financeos.domain.account.Account;
import com.financeos.domain.categorization.CategoryAssigner;
import com.financeos.domain.statement.Statement;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.domain.transaction.ReviewStatusManager;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.match.StatementBatchMatcher;
import com.financeos.domain.user.User;
import com.financeos.gmail.reconcile.ParsedStatementLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** The shared refresh + match + create-rows block the upload path and the re-read job both run. */
class ReconciledRefreshTest {

    private StatementPersistenceService persistence;
    private StatementBatchMatcher matcher;
    private ReviewStatusManager review;
    private FileIngestionDbHandler db;
    private CategoryAssigner assigner;
    private ReconciledRefresh refresh;
    private final User user = new User();
    private final Account account = new Account();
    private final UUID accountId = UUID.randomUUID();
    private final StatementDraft draft = new StatementDraft("bank_account", null, null, null, null, null, null, 0, 0,
            null, null, null, null, null, null, Map.of());
    private final List<ParsedStatementLine> lines = List.of(
            new ParsedStatementLine(LocalDate.of(2026, 1, 5), new BigDecimal("-10.50"), "DEBIT", "Coffee", null, null),
            new ParsedStatementLine(LocalDate.of(2026, 1, 6), new BigDecimal("20"), "CREDIT", "Refund", null, null));

    @BeforeEach
    void setUp() {
        persistence = mock(StatementPersistenceService.class);
        matcher = mock(StatementBatchMatcher.class);
        review = mock(ReviewStatusManager.class);
        db = mock(FileIngestionDbHandler.class);
        assigner = mock(CategoryAssigner.class);
        refresh = new ReconciledRefresh(persistence, matcher, review, db, assigner);
    }

    private StatementPersistenceService.Refreshed refreshed(List<Integer> missing) {
        return new StatementPersistenceService.Refreshed(new Statement(), List.of("period"), missing, List.of());
    }

    @Test
    void nothingToRefreshIsEmptyAndTheMatcherIsNeverCalled() {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(refresh.apply(user, account, accountId, "sha", draft, lines, Set.of())).isEmpty();
        verifyNoInteractions(matcher, db);
    }

    @Test
    void aNullFromARefreshMockIsTreatedAsEmpty() {
        assertThat(refresh.apply(user, account, accountId, "sha", draft, lines, Set.of())).isEmpty();
    }

    @Test
    void noMissingLinesMeansNoMatcherAndNoRows() {
        when(persistence.refreshReconciled(accountId, "sha", draft, lines, Set.of())).thenReturn(Optional.of(refreshed(List.of())));

        Optional<ReconciledRefresh.Applied> out = refresh.apply(user, account, accountId, "sha", draft, lines, Set.of());

        assertThat(out).isPresent();
        assertThat(out.get().matched()).isNull();
        verifyNoInteractions(matcher, db);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingLinesGoToTheMatcherUnderTheirParsedIndexAndUnpairedOnesBecomeRows() {
        when(persistence.refreshReconciled(any(), any(), any(), any(), any())).thenReturn(Optional.of(refreshed(List.of(1))));
        StatementBatchMatcher.Result result = new StatementBatchMatcher.Result(0, 0, 1, 0, 0, List.of());
        when(matcher.match(any(), any(), anyList(), any())).thenAnswer(inv -> {
            StatementBatchMatcher.LineCreator creator = inv.getArgument(3);
            List<StatementBatchMatcher.Line> batch = inv.getArgument(2);
            creator.create(batch);
            return result;
        });

        Optional<ReconciledRefresh.Applied> out = refresh.apply(user, account, accountId, "sha", draft, lines, Set.of());

        assertThat(out.get().matched()).isSameAs(result);
        ArgumentCaptor<List<StatementBatchMatcher.Line>> batch = ArgumentCaptor.forClass(List.class);
        verify(matcher).match(any(), eq(account), batch.capture(), any());
        assertThat(batch.getValue()).extracting(StatementBatchMatcher.Line::lineIndex).containsExactly(1);
        ArgumentCaptor<List<Transaction>> saved = ArgumentCaptor.forClass(List.class);
        verify(db, times(1)).saveTransactions(saved.capture(), eq(List.of()));
        Transaction t = saved.getValue().get(0);
        assertThat(t.getSource()).isEqualTo(TransactionSource.file_upload);
        assertThat(t.getType()).isEqualTo(TransactionType.CREDIT);
        assertThat(t.getAmount()).isEqualByComparingTo("20");
        assertThat(t.getSourcedDescription()).isEqualTo("Refund");
        assertThat(t.getUser()).isSameAs(user);
        assertThat(t.getAccount()).isSameAs(account);
        assertThat(t.isTransactionUnderMonitoring()).isFalse();
        assertThat(t.isTransactionExcluded()).isFalse();
        verify(review).transitionTo(eq(t), eq(com.financeos.domain.transaction.ReviewType.AUTO_REVIEWED));
        verify(assigner).assignOnArrival(saved.getValue());
    }

    @Test
    void createRowsKeepsTheMatcherIndexesAndAbsAmounts() {
        Map<Integer, Transaction> made = refresh.createRows(user, account, lines, List.of(
                StatementBatchMatcher.Line.of(0, lines.get(0)), StatementBatchMatcher.Line.of(1, lines.get(1))));

        assertThat(made).containsOnlyKeys(0, 1);
        assertThat(made.get(0).getAmount()).isEqualByComparingTo("10.50");
        assertThat(made.get(0).getType()).isEqualTo(TransactionType.DEBIT);
    }

    @Test
    void aFailingCategoriserNeverStopsTheRowsBeingSaved() {
        when(assigner.assignOnArrival(anyList())).thenThrow(new IllegalStateException("boom"));

        refresh.createRows(user, account, lines, List.of(StatementBatchMatcher.Line.of(0, lines.get(0))));

        verify(db).saveTransactions(anyList(), eq(List.of()));
    }

    @Test
    void withoutACategoriserRowsAreStillSaved() {
        new ReconciledRefresh(persistence, matcher, review, db, (CategoryAssigner) null)
                .createRows(user, account, lines, List.of(StatementBatchMatcher.Line.of(0, lines.get(0))));

        verify(db).saveTransactions(anyList(), eq(List.of()));
    }
}
