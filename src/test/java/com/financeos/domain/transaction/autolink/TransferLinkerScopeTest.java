package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionType;
import com.financeos.domain.transaction.autolink.TransferLinker.Plan;
import com.financeos.domain.transaction.link.TransactionLinkService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TransferLinkerScopeTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 10);
    private static final UUID BANK_A = UUID.randomUUID();
    private static final UUID BANK_B = UUID.randomUUID();
    private static final UUID BANK_C = UUID.randomUUID();

    private static AutoLinkCandidate tx(UUID account, LocalDate date, String amount, TransactionType type) {
        return new AutoLinkCandidate(UUID.randomUUID(), account, AccountType.bank_account, date, new BigDecimal(amount), type, "x");
    }

    @Test
    void onlyDebitsInsideTheAnchorRangeAreSuggested() {
        AutoLinkCandidate inside = tx(BANK_A, D, "100", TransactionType.DEBIT);
        AutoLinkCandidate outside = tx(BANK_A, D.plusDays(30), "100", TransactionType.DEBIT);
        // two credits each so both debits are ambiguous and would be suggestions in a full pass
        List<AutoLinkCandidate> all = List.of(inside, outside,
                tx(BANK_B, D, "100", TransactionType.CREDIT), tx(BANK_C, D, "100", TransactionType.CREDIT),
                tx(BANK_B, D.plusDays(30), "100", TransactionType.CREDIT), tx(BANK_C, D.plusDays(30), "100", TransactionType.CREDIT));

        Plan scoped = TransferLinker.plan(all, Set.of(), new LinkScope(D.minusDays(2), D.plusDays(2)));
        Plan full = TransferLinker.plan(all, Set.of());

        assertThat(scoped.suggestions()).extracting(TransferLinker.Suggestion::transactionId).containsExactly(inside.id());
        assertThat(full.suggestions()).extracting(TransferLinker.Suggestion::transactionId)
                .containsExactlyInAnyOrder(inside.id(), outside.id());
    }

    @Test
    void aDebitJustOutsideTheAnchorsStillMakesACreditAmbiguousForAnAnchorDebit() {
        // The credit fits two debits; only one is an anchor. Linking it would be wrong: the other debit also fits.
        AutoLinkCandidate anchorDebit = tx(BANK_A, D, "100", TransactionType.DEBIT);
        AutoLinkCandidate farDebit = tx(BANK_C, D.plusDays(3), "100", TransactionType.DEBIT);
        AutoLinkCandidate credit = tx(BANK_B, D.plusDays(2), "100", TransactionType.CREDIT);

        Plan scoped = TransferLinker.plan(List.of(anchorDebit, farDebit, credit), Set.of(), new LinkScope(D, D));

        assertThat(scoped.autoLinks()).isEmpty();
        assertThat(scoped.suggestions()).extracting(TransferLinker.Suggestion::transactionId).containsExactly(anchorDebit.id());
    }

    @Test
    void aUniquePairInsideTheAnchorsIsLinkedAndOneOutsideIsNot() {
        AutoLinkCandidate debit = tx(BANK_A, D, "100", TransactionType.DEBIT);
        AutoLinkCandidate credit = tx(BANK_B, D.plusDays(1), "100", TransactionType.CREDIT);

        assertThat(TransferLinker.plan(List.of(debit, credit), Set.of(), new LinkScope(D, D)).autoLinks()).hasSize(1);
        assertThat(TransferLinker.plan(List.of(debit, credit), Set.of(), new LinkScope(D.plusDays(10), D.plusDays(11))).autoLinks())
                .isEmpty();
    }

    @Test
    void aScopedRunLoadsThreeWindowsAndSyncsOnlyTheAnchorRange() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        UUID userId = UUID.randomUUID();
        LinkScope scope = new LinkScope(D, D.plusDays(1));
        when(loader.load(eq(userId), any(LinkScope.class))).thenReturn(List.of());
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());

        new TransferLinker(loader, store, mock(TransactionLinkService.class), tm).link(userId, scope);

        verify(loader).load(userId, new LinkScope(D.minusDays(6), D.plusDays(7)));
        verify(store).syncSuggestions(eq(userId), eq(Set.of(SuggestionKind.TRANSFER, SuggestionKind.CC_PAYMENT)), any(),
                eq(new LinkScope(D.minusDays(2), D.plusDays(3))));
    }

    @Test
    void aFullRunLoadsEverythingAndSyncsEverything() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        UUID userId = UUID.randomUUID();
        when(loader.load(userId)).thenReturn(List.of());
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());

        new TransferLinker(loader, store, mock(TransactionLinkService.class), tm).link(userId);

        verify(loader).load(userId);
        verify(loader, never()).load(eq(userId), any(LinkScope.class));
        verify(store).syncSuggestions(eq(userId), any(), any());
        verify(store, never()).syncSuggestions(any(), any(), any(), any());
    }
}
