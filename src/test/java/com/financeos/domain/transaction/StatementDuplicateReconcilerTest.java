package com.financeos.domain.transaction;

import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StatementDuplicateReconcilerTest {

    private static final LocalDate START = LocalDate.of(2026, 8, 1);
    private static final LocalDate END = LocalDate.of(2026, 8, 31);

    private TransactionService transactionService;
    private TransactionRepository transactionRepository;
    private GmailProcessedMessageRepository processedRepo;
    private StatementDuplicateReconciler reconciler;

    @BeforeEach
    void setUp() {
        transactionService = mock(TransactionService.class);
        transactionRepository = mock(TransactionRepository.class);
        processedRepo = mock(GmailProcessedMessageRepository.class);
        when(processedRepo.findByTransactionId(any())).thenReturn(Optional.empty());
        reconciler = new StatementDuplicateReconciler(new TransactionMatcher(), transactionService,
                transactionRepository, processedRepo, new ReviewStatusManager());
    }

    private static Transaction row(TransactionSource source, LocalDate date, String amount, String text, Instant created) {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        t.setSource(source);
        t.setDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setType(TransactionType.DEBIT);
        if (source == TransactionSource.manual) t.setDescription(text); else t.setSourcedDescription(text);
        org.springframework.test.util.ReflectionTestUtils.setField(t, "createdAt", created);
        return t;
    }

    private static Transaction statement(LocalDate date, String text) {
        return row(TransactionSource.file_upload, date, "450.00", text, Instant.now());
    }

    @Test
    void manualRowTwoDaysEarlierInsidePeriodWithSameMerchantIsMerged() {
        Transaction stmt = statement(LocalDate.of(2026, 8, 12), "UPI/DR/4123/SWIGGY/YESB");
        Transaction manual = row(TransactionSource.manual, LocalDate.of(2026, 8, 10), "450.00", "Swiggy", Instant.now());
        when(transactionService.mergeStatementDuplicate(stmt.getId(), manual.getId())).thenReturn(true);

        List<StatementDuplicateReconciler.Pair> pairs = reconciler.pair(List.of(stmt), List.of(manual), START, END, Set.of());
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).certain()).isTrue();
        assertThat(reconciler.settle(pairs)).isEqualTo(1);

        verify(transactionService).mergeStatementDuplicate(stmt.getId(), manual.getId());
        assertThat(stmt.getReviewReasons()).doesNotContain(ReviewReason.DUPLICATE_SUSPECT);
    }

    @Test
    void differentMerchantIsFlaggedNotMerged() {
        Transaction stmt = statement(LocalDate.of(2026, 8, 12), "UPI/DR/4123/ZOMATO/YESB");
        Transaction manual = row(TransactionSource.manual, LocalDate.of(2026, 8, 10), "450.00", "Swiggy", Instant.now());

        List<StatementDuplicateReconciler.Pair> pairs = reconciler.pair(List.of(stmt), List.of(manual), START, END, Set.of());
        assertThat(pairs.get(0).certain()).isFalse();
        assertThat(reconciler.settle(pairs)).isZero();

        verify(transactionService, never()).mergeStatementDuplicate(any(), any());
        assertThat(stmt.getReviewReasons()).contains(ReviewReason.DUPLICATE_SUSPECT);
        assertThat(manual.getReviewReasons()).contains(ReviewReason.DUPLICATE_SUSPECT);
    }

    @Test
    void existingRowOutsideThePeriodIsFlaggedNotMerged() {
        Transaction stmt = statement(LocalDate.of(2026, 8, 1), "UPI/DR/4123/SWIGGY/YESB");
        Transaction manual = row(TransactionSource.manual, LocalDate.of(2026, 7, 30), "450.00", "Swiggy", Instant.now());

        List<StatementDuplicateReconciler.Pair> pairs = reconciler.pair(List.of(stmt), List.of(manual), START, END, Set.of());
        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).certain()).isFalse();
        reconciler.settle(pairs);

        verify(transactionService, never()).mergeStatementDuplicate(any(), any());
        assertThat(manual.getReviewReasons()).contains(ReviewReason.DUPLICATE_SUSPECT);
    }

    @Test
    void beyondTwoDaysIsNeverPaired() {
        Transaction stmt = statement(LocalDate.of(2026, 8, 12), "SWIGGY");
        Transaction manual = row(TransactionSource.manual, LocalDate.of(2026, 8, 9), "450.00", "Swiggy", Instant.now());
        assertThat(reconciler.pair(List.of(stmt), List.of(manual), START, END, Set.of())).isEmpty();
    }

    @Test
    void statementRowsAreNeverPairedWithStatementRows() {
        Transaction stmt = statement(LocalDate.of(2026, 8, 12), "UPI/DR/4123/SWIGGY/YESB");
        Transaction other = row(TransactionSource.gmail_statement, LocalDate.of(2026, 8, 12), "450.00", "UPI/DR/4123/SWIGGY/YESB", Instant.now());
        Transaction other2 = row(TransactionSource.file_upload, LocalDate.of(2026, 8, 11), "450.00", "SWIGGY", Instant.now());

        assertThat(reconciler.pair(List.of(stmt), List.of(other, other2), START, END, Set.of())).isEmpty();
    }

    @Test
    void pairingIsOneToOneClosestDateFirst() {
        Transaction stmtA = statement(LocalDate.of(2026, 8, 12), "SWIGGY");
        Transaction stmtB = statement(LocalDate.of(2026, 8, 14), "SWIGGY");
        Transaction near = row(TransactionSource.manual, LocalDate.of(2026, 8, 12), "450.00", "Swiggy", Instant.now());
        Transaction far = row(TransactionSource.gmail_transaction_alert, LocalDate.of(2026, 8, 13), "450.00", "Swiggy", Instant.now());

        List<StatementDuplicateReconciler.Pair> pairs = reconciler.pair(List.of(stmtA, stmtB), List.of(near, far), START, END, Set.of());

        assertThat(pairs).hasSize(2);
        assertThat(pairs).filteredOn(p -> p.statementRow() == stmtA).extracting(StatementDuplicateReconciler.Pair::existing).containsExactly(near);
        assertThat(pairs).filteredOn(p -> p.statementRow() == stmtB).extracting(StatementDuplicateReconciler.Pair::existing).containsExactly(far);
    }

    @Test
    void oneExistingRowIsClaimedByOnlyOneStatementRowEarliestCreatedWinsATie() {
        Transaction stmt = statement(LocalDate.of(2026, 8, 12), "SWIGGY");
        Transaction older = row(TransactionSource.manual, LocalDate.of(2026, 8, 11), "450.00", "Swiggy", Instant.parse("2026-08-11T10:00:00Z"));
        Transaction newer = row(TransactionSource.manual, LocalDate.of(2026, 8, 13), "450.00", "Swiggy", Instant.parse("2026-08-13T10:00:00Z"));

        List<StatementDuplicateReconciler.Pair> pairs = reconciler.pair(List.of(stmt), List.of(newer, older), START, END, Set.of());

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).existing()).isSameAs(older);
    }

    @Test
    void excludedExistingRowsAreSkippedAndAbsorbedAlertMailMovesToTheStatementRow() {
        Transaction stmt = statement(LocalDate.of(2026, 8, 12), "SWIGGY");
        Transaction alert = row(TransactionSource.gmail_transaction_alert, LocalDate.of(2026, 8, 12), "450.00", "Swiggy", Instant.now());
        assertThat(reconciler.pair(List.of(stmt), List.of(alert), START, END, Set.of(alert.getId()))).isEmpty();

        GmailProcessedMessage mail = new GmailProcessedMessage();
        when(processedRepo.findByTransactionId(alert.getId())).thenReturn(Optional.of(mail));
        when(transactionService.mergeStatementDuplicate(stmt.getId(), alert.getId())).thenReturn(true);
        Transaction ref = new Transaction();
        when(transactionRepository.getReferenceById(stmt.getId())).thenReturn(ref);

        reconciler.settle(reconciler.pair(List.of(stmt), List.of(alert), START, END, Set.of()));

        assertThat(mail.getTransaction()).isSameAs(ref);
        verify(processedRepo).save(mail);
    }
}
