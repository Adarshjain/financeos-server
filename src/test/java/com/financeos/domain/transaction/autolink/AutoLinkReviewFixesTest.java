package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.exception.ValidationException;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AutoLinkReviewFixesTest {

    private final UUID userId = UUID.randomUUID();

    private AutoLinkService service(AutoLinker... linkers) {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return new AutoLinkService(List.of(linkers), tm, mock(NotificationFeedService.class));
    }

    private static AutoLinker linker(java.util.function.Supplier<AutoLinkOutcome> body) {
        return new AutoLinker() {
            public Set<SuggestionKind> ownedKinds() {
                return Set.of();
            }

            public AutoLinkOutcome link(UUID id) {
                return body.get();
            }
        };
    }

    @Test
    void aFailedPairDoesNotBlockTheBackfillMarker() {
        AutoLinkService.RunResult r = service(linker(() -> AutoLinkOutcome.of(java.util.Map.of(), true))).runQuietly(userId);

        assertThat(r.clean()).isTrue();
    }

    @Test
    void aLinkerLevelFailureStillLeavesTheRunUnclean() {
        AutoLinkService.RunResult r = service(linker(() -> {
            throw new IllegalStateException("boom");
        })).runQuietly(userId);

        assertThat(r.clean()).isFalse();
    }

    @Test
    void refundSuggesterRunsAfterEmiLendingAndDividendLinkers() {
        int refund = RefundSuggester.class.getAnnotation(Order.class).value();
        assertThat(refund).isGreaterThan(DividendAutoLinker.class.getAnnotation(Order.class).value());
        assertThat(DividendAutoLinker.class.getAnnotation(Order.class).value())
                .isGreaterThan(LendingAutoLinker.class.getAnnotation(Order.class).value());
        assertThat(LendingAutoLinker.class.getAnnotation(Order.class).value())
                .isGreaterThan(EmiAutoLinker.class.getAnnotation(Order.class).value());
    }

    @Test
    void acceptRejectsASuggestionWhoseTransactionWasClaimedByALoanLendingOrDividend() {
        AutoLinkStore store = mock(AutoLinkStore.class);
        LinkSuggestionService suggestions = new LinkSuggestionService(store, mock(TransactionRepository.class),
                mock(TransactionLinkService.class));
        UUID main = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        AutoLinkStore.SuggestionRow row = new AutoLinkStore.SuggestionRow(UUID.randomUUID(), SuggestionKind.TRANSFER, main, List.of(candidate));
        when(store.findSuggestion(userId, row.id())).thenReturn(Optional.of(row));
        when(store.ownedTransactionIds(eq(userId), any())).thenReturn(Set.of(candidate));

        assertThatThrownBy(() -> suggestions.accept(userId, row.id(), candidate)).isInstanceOf(ValidationException.class);
        verify(store, never()).deleteSuggestion(any(), any());
    }
}
