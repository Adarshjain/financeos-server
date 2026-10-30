package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.investment.dividend.DividendMatchTier;
import com.financeos.domain.investment.dividend.DividendReceiptService;
import com.financeos.domain.investment.dividend.DividendReceiptService.UniqueMatch;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class DividendAutoLinkerTest {

    private final AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
    private final AutoLinkStore store = mock(AutoLinkStore.class);
    private final DividendReceiptService receipts = mock(DividendReceiptService.class);
    private final UUID userId = UUID.randomUUID();

    private DividendAutoLinker linker() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return new DividendAutoLinker(loader, store, receipts, tm);
    }

    @Test
    void linksEachUniqueMatchThroughTheConfirmPath() {
        UniqueMatch m = new UniqueMatch(UUID.randomUUID(), UUID.randomUUID(), DividendMatchTier.EXACT);
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(receipts.findUniqueMatches(any())).thenReturn(List.of(m));
        when(receipts.isUnlinked(m.dividendId())).thenReturn(true);
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(m.transactionId()));

        AutoLinkOutcome outcome = linker().link(userId);

        verify(receipts).autoLinkTransaction(m.dividendId(), m.transactionId());
        assertThat(outcome.byKind().get(SuggestionKind.DIVIDEND).linked()).isEqualTo(1);
        assertThat(outcome.partial()).isFalse();
    }

    @Test
    void theRejectionPredicateReadsTheStoredDividendPairsInEitherDirection() {
        UUID dividend = UUID.randomUUID();
        UUID tx = UUID.randomUUID();
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of(AutoLinkStore.pairKey(SuggestionKind.DIVIDEND, tx, dividend)));
        when(receipts.findUniqueMatches(any())).thenReturn(List.of());

        linker().link(userId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<BiPredicate<UUID, UUID>> predicate = ArgumentCaptor.forClass(BiPredicate.class);
        verify(receipts).findUniqueMatches(predicate.capture());
        assertThat(predicate.getValue().test(dividend, tx)).isTrue();
        assertThat(predicate.getValue().test(dividend, UUID.randomUUID())).isFalse();
        assertThat(predicate.getValue().test(UUID.randomUUID(), tx)).isFalse();
    }

    @Test
    void aCreditNoLongerFreeIsNotLinked() {
        UniqueMatch m = new UniqueMatch(UUID.randomUUID(), UUID.randomUUID(), DividendMatchTier.NET_OF_TDS);
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(receipts.findUniqueMatches(any())).thenReturn(List.of(m));
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of());

        AutoLinkOutcome outcome = linker().link(userId);

        verify(receipts, never()).autoLinkTransaction(any(), any());
        assertThat(outcome.linked()).isZero();
    }

    @Test
    void aFailingPairMakesTheOutcomePartialAndTheNextStillLinks() {
        UniqueMatch bad = new UniqueMatch(UUID.randomUUID(), UUID.randomUUID(), DividendMatchTier.EXACT);
        UniqueMatch good = new UniqueMatch(UUID.randomUUID(), UUID.randomUUID(), DividendMatchTier.EXACT);
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(receipts.findUniqueMatches(any())).thenReturn(List.of(bad, good));
        when(receipts.isUnlinked(any())).thenReturn(true);
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(bad.transactionId(), good.transactionId()));
        doThrow(new IllegalStateException("boom")).when(receipts).autoLinkTransaction(bad.dividendId(), bad.transactionId());

        AutoLinkOutcome outcome = linker().link(userId);

        verify(receipts).autoLinkTransaction(good.dividendId(), good.transactionId());
        assertThat(outcome.linked()).isEqualTo(1);
        assertThat(outcome.partial()).isTrue();
    }

    @Test
    void aDividendLinkedMeanwhileIsNeverOverwritten() {
        UniqueMatch m = new UniqueMatch(UUID.randomUUID(), UUID.randomUUID(), DividendMatchTier.EXACT);
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());
        when(receipts.findUniqueMatches(any())).thenReturn(List.of(m));
        when(receipts.isUnlinked(m.dividendId())).thenReturn(false);
        when(loader.freeIds(eq(userId), any())).thenReturn(Set.of(m.transactionId()));

        AutoLinkOutcome outcome = linker().link(userId);

        verify(receipts, never()).autoLinkTransaction(any(), any());
        assertThat(outcome.linked()).isZero();
    }
}
