package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.lending.LendingService.UnlinkedEntry;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.LendingAutoLinker.Pair;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class LendingAutoLinkerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 3, 5);
    private static final UUID BANK = UUID.randomUUID();

    private static PlatformTransactionManager tm() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return tm;
    }

    private static UnlinkedEntry entry(LendingDirection direction, String amount, LocalDate date) {
        return new UnlinkedEntry(UUID.randomUUID(), direction, new BigDecimal(amount), date);
    }

    private static AutoLinkCandidate tx(TransactionType type, String amount, LocalDate date) {
        return new AutoLinkCandidate(UUID.randomUUID(), BANK, AccountType.bank_account, date, new BigDecimal(amount), type, "UPI RAHUL");
    }

    @Test
    void aUniqueExactMatchLinks() {
        UnlinkedEntry e = entry(LendingDirection.lent, "5000", DAY);
        AutoLinkCandidate t = tx(TransactionType.DEBIT, "5000.00", DAY.plusDays(3));

        List<Pair> pairs = LendingAutoLinker.plan(List.of(e), List.of(t), Set.of());

        assertThat(pairs).hasSize(1);
        assertThat(pairs.get(0).entry()).isEqualTo(e);
        assertThat(pairs.get(0).transactionId()).isEqualTo(t.id());
    }

    @Test
    void theDirectionDecidesDebitOrCredit() {
        UnlinkedEntry lent = entry(LendingDirection.lent, "5000", DAY);
        UnlinkedEntry borrowed = entry(LendingDirection.borrowed, "5000", DAY);
        AutoLinkCandidate credit = tx(TransactionType.CREDIT, "5000", DAY);

        assertThat(LendingAutoLinker.plan(List.of(lent), List.of(credit), Set.of())).isEmpty();
        assertThat(LendingAutoLinker.plan(List.of(borrowed), List.of(credit), Set.of())).hasSize(1);
    }

    @Test
    void anInexactAmountIsNotLinkedEvenWithinTheViewsTwentyRupees() {
        UnlinkedEntry e = entry(LendingDirection.lent, "5000", DAY);

        assertThat(LendingAutoLinker.plan(List.of(e), List.of(tx(TransactionType.DEBIT, "5010", DAY)), Set.of())).isEmpty();
        assertThat(LendingAutoLinker.plan(List.of(e), List.of(tx(TransactionType.DEBIT, "4999.99", DAY)), Set.of())).isEmpty();
    }

    @Test
    void anEntryWithTwoCandidatesIsNotLinked() {
        UnlinkedEntry e = entry(LendingDirection.lent, "5000", DAY);

        List<Pair> pairs = LendingAutoLinker.plan(List.of(e),
                List.of(tx(TransactionType.DEBIT, "5000", DAY), tx(TransactionType.DEBIT, "5000", DAY.plusDays(2))), Set.of());

        assertThat(pairs).isEmpty();
    }

    @Test
    void aTransactionFittingTwoEntriesIsNotLinked() {
        UnlinkedEntry a = entry(LendingDirection.lent, "5000", DAY);
        UnlinkedEntry b = entry(LendingDirection.lent, "5000", DAY.plusDays(1));

        assertThat(LendingAutoLinker.plan(List.of(a, b), List.of(tx(TransactionType.DEBIT, "5000", DAY)), Set.of())).isEmpty();
    }

    @Test
    void aRejectedPairIsSkipped() {
        UnlinkedEntry e = entry(LendingDirection.lent, "5000", DAY);
        AutoLinkCandidate t = tx(TransactionType.DEBIT, "5000", DAY);
        Set<String> rejected = Set.of(AutoLinkStore.pairKey(SuggestionKind.LENDING, t.id(), e.id()));

        assertThat(LendingAutoLinker.plan(List.of(e), List.of(t), rejected)).isEmpty();
    }

    @Test
    void aTransactionOutsideTheSevenDayWindowIsNotLinked() {
        UnlinkedEntry e = entry(LendingDirection.lent, "5000", DAY);

        assertThat(LendingAutoLinker.plan(List.of(e), List.of(tx(TransactionType.DEBIT, "5000", DAY.plusDays(8))), Set.of())).isEmpty();
    }

    @Test
    void linkWritesThroughTheConfirmPathAndCounts() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        LendingService lendingService = mock(LendingService.class);
        UUID userId = UUID.randomUUID();
        UnlinkedEntry e = entry(LendingDirection.lent, "5000", DAY);
        AutoLinkCandidate t = tx(TransactionType.DEBIT, "5000", DAY);
        when(lendingService.getUnlinkedEntries()).thenReturn(List.of(e));
        when(lendingService.isUnlinked(e.id())).thenReturn(true);
        when(loader.load(userId)).thenReturn(List.of(t));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(t.id()));

        AutoLinkOutcome outcome = new LendingAutoLinker(loader, store, lendingService, tm()).link(userId);

        verify(lendingService).autoLinkTransaction(e.id(), t.id());
        assertThat(outcome.byKind().get(SuggestionKind.LENDING).linked()).isEqualTo(1);
    }

    @Test
    void aTransactionNoLongerFreeIsNotLinked() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        LendingService lendingService = mock(LendingService.class);
        UUID userId = UUID.randomUUID();
        UnlinkedEntry e = entry(LendingDirection.lent, "5000", DAY);
        AutoLinkCandidate t = tx(TransactionType.DEBIT, "5000", DAY);
        when(lendingService.getUnlinkedEntries()).thenReturn(List.of(e));
        when(loader.load(userId)).thenReturn(List.of(t));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of());

        AutoLinkOutcome outcome = new LendingAutoLinker(loader, store, lendingService, tm()).link(userId);

        verify(lendingService, never()).autoLinkTransaction(any(), any());
        assertThat(outcome.linked()).isZero();
    }

    @Test
    void anEntryLinkedMeanwhileIsNeverOverwritten() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        LendingService lendingService = mock(LendingService.class);
        UUID userId = UUID.randomUUID();
        UnlinkedEntry e = entry(LendingDirection.lent, "5000", DAY);
        AutoLinkCandidate t = tx(TransactionType.DEBIT, "5000", DAY);
        when(lendingService.getUnlinkedEntries()).thenReturn(List.of(e));
        when(lendingService.isUnlinked(e.id())).thenReturn(false);
        when(loader.load(userId)).thenReturn(List.of(t));
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(t.id()));

        AutoLinkOutcome outcome = new LendingAutoLinker(loader, store, lendingService, tm()).link(userId);

        verify(lendingService, never()).autoLinkTransaction(any(), any());
        assertThat(outcome.linked()).isZero();
    }

    @Test
    void aRepaymentThatNamesAPersonIsSuggestedAndARememberedOneIsNot() {
        UUID credit = UUID.randomUUID();
        UUID person = UUID.randomUUID();
        UUID lending = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<LendingService.RepaymentSuggestion> found = List.of(
                new LendingService.RepaymentSuggestion(credit, person, "Rahul", lending),
                new LendingService.RepaymentSuggestion(other, person, "Rahul", lending));
        Set<String> rejected = Set.of(AutoLinkStore.pairKey(SuggestionKind.LENDING, other, person));

        assertThat(LendingAutoLinker.repaymentSuggestions(rejected, found)).containsOnlyKeys(credit)
                .containsEntry(credit, List.of(lending));
    }

    @Test
    void linkStoresTheLendingSuggestionsEvenWithNoUnlinkedEntries() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        LendingService lendingService = mock(LendingService.class);
        UUID userId = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        UUID lending = UUID.randomUUID();
        when(lendingService.getUnlinkedEntries()).thenReturn(List.of());
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(lendingService.getRepaymentSuggestions()).thenReturn(List.of(
                new LendingService.RepaymentSuggestion(credit, UUID.randomUUID(), "Rahul", lending)));

        LendingAutoLinker linker = new LendingAutoLinker(loader, store, lendingService, tm());
        AutoLinkOutcome outcome = linker.link(userId);

        assertThat(linker.ownedKinds()).containsExactly(SuggestionKind.LENDING);
        verify(store).syncSuggestions(userId, Set.of(SuggestionKind.LENDING),
                java.util.Map.of(SuggestionKind.LENDING, java.util.Map.of(credit, List.of(lending))));
        assertThat(outcome.suggested()).isEqualTo(1);
        assertThat(outcome.linked()).isZero();
    }
}
