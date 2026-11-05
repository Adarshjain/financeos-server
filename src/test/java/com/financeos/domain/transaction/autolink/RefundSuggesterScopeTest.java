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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundSuggesterScopeTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 10);
    private static final UUID ACC = UUID.randomUUID();

    private static AutoLinkCandidate tx(LocalDate date, String amount, TransactionType type) {
        return new AutoLinkCandidate(UUID.randomUUID(), ACC, AccountType.credit_card, date, new BigDecimal(amount), type, "SWIGGY");
    }

    @Test
    void onlyCreditsInsideTheAnchorRangeGetASuggestion() {
        AutoLinkCandidate debit = tx(D, "500", TransactionType.DEBIT);
        AutoLinkCandidate nearCredit = tx(D.plusDays(5), "500", TransactionType.CREDIT);
        AutoLinkCandidate laterCredit = tx(D.plusDays(40), "500", TransactionType.CREDIT);
        List<AutoLinkCandidate> all = List.of(debit, nearCredit, laterCredit);

        Map<UUID, List<UUID>> scoped = RefundSuggester.plan(all, Set.of(), new LinkScope(D.plusDays(4), D.plusDays(6)));
        Map<UUID, List<UUID>> full = RefundSuggester.plan(all, Set.of());

        assertThat(scoped).containsOnlyKeys(nearCredit.id());
        assertThat(scoped.get(nearCredit.id())).containsExactly(debit.id());
        assertThat(full).containsOnlyKeys(nearCredit.id(), laterCredit.id());
    }

    @Test
    void aScopedRunLoadsThreeWindowsAndSyncsOnlyTheAnchorRange() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        UUID userId = UUID.randomUUID();
        LinkScope scope = new LinkScope(D, D);
        when(loader.load(eq(userId), any(LinkScope.class))).thenReturn(List.of());
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());

        new RefundSuggester(loader, store).link(userId, scope);

        verify(loader).load(userId, new LinkScope(D.minusDays(180), D.plusDays(180)));
        verify(store).syncSuggestions(eq(userId), eq(Set.of(SuggestionKind.REFUND)), any(),
                eq(new LinkScope(D.minusDays(60), D.plusDays(60))));
    }

    @Test
    void aFullRunKeepsTheFullSync() {
        AutoLinkCandidateLoader loader = mock(AutoLinkCandidateLoader.class);
        AutoLinkStore store = mock(AutoLinkStore.class);
        UUID userId = UUID.randomUUID();
        when(loader.load(userId)).thenReturn(List.of());
        when(store.loadRejectionKeys(userId)).thenReturn(Set.of());

        new RefundSuggester(loader, store).link(userId);

        verify(loader).load(userId);
        verify(loader, never()).load(eq(userId), any(LinkScope.class));
        verify(store).syncSuggestions(eq(userId), any(), any());
        verify(store, never()).syncSuggestions(any(), any(), any(), any());
    }
}
