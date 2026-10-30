package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.core.security.UserContext;
import com.financeos.domain.notification.feed.AppNotificationKind;
import com.financeos.domain.notification.feed.AppNotificationRefs;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.domain.transaction.autolink.AutoLinkOutcome.Counts;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AutoLinkServiceTest {

    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final NotificationFeedService feed = mock(NotificationFeedService.class);
    private final UUID userId = UUID.randomUUID();

    private AutoLinkService service(AutoLinker... linkers) {
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return new AutoLinkService(List.of(linkers), tm, feed);
    }

    private static AutoLinkOutcome outcome(SuggestionKind kind, int linked, int suggested) {
        Map<SuggestionKind, Counts> m = new EnumMap<>(SuggestionKind.class);
        m.put(kind, new Counts(linked, suggested));
        return AutoLinkOutcome.of(m);
    }

    private static AutoLinker linker(Set<SuggestionKind> kinds, java.util.function.Supplier<AutoLinkOutcome> body) {
        return new AutoLinker() {
            @Override
            public Set<SuggestionKind> ownedKinds() {
                return kinds;
            }

            @Override
            public AutoLinkOutcome link(UUID id) {
                return body.get();
            }
        };
    }

    @Test
    void runsEveryLinkerInOrderWithTheUserContextSetAndSumsTheCounts() {
        List<String> order = new java.util.ArrayList<>();
        UUID[] seen = new UUID[1];
        AutoLinker first = linker(Set.of(SuggestionKind.TRANSFER), () -> {
            order.add("transfer");
            seen[0] = UserContext.getCurrentUserId();
            return outcome(SuggestionKind.TRANSFER, 2, 1);
        });
        AutoLinker second = linker(Set.of(SuggestionKind.REFUND), () -> {
            order.add("refund");
            return outcome(SuggestionKind.REFUND, 0, 3);
        });

        AutoLinkService.RunResult result = service(first, second).run(userId);

        assertThat(order).containsExactly("transfer", "refund");
        assertThat(seen[0]).isEqualTo(userId);
        assertThat(UserContext.getCurrentUserId()).isNull();
        assertThat(result.linked()).isEqualTo(2);
        assertThat(result.suggested()).isEqualTo(4);
        assertThat(result.byKind().get(SuggestionKind.TRANSFER)).isEqualTo(new Counts(2, 1));
    }

    @Test
    void aFailedLinkerMakesTheRunNotCleanAndASkippedCallIsNotCleanEither() {
        AutoLinker broken = linker(Set.of(SuggestionKind.TRANSFER), () -> {
            throw new IllegalStateException("boom");
        });
        AutoLinker fine = linker(Set.of(SuggestionKind.REFUND), () -> outcome(SuggestionKind.REFUND, 0, 1));

        assertThat(service(broken, fine).run(userId).clean()).isFalse();
        assertThat(service(fine).run(userId).clean()).isTrue();
        assertThat(AutoLinkService.RunResult.EMPTY.clean()).isFalse();
    }

    @Test
    void aNotificationFailureNeverEscapesRun() {
        org.mockito.Mockito.doThrow(new IllegalStateException("feed down")).when(feed).record(any(), any(), any(), any(), any(), any());

        AutoLinkService.RunResult result = service(linker(Set.of(SuggestionKind.TRANSFER), () -> outcome(SuggestionKind.TRANSFER, 1, 0))).run(userId);

        assertThat(result.linked()).isEqualTo(1);
    }

    @Test
    void aFailingLinkerIsLoggedAndTheNextOneStillRuns() {
        AutoLinker broken = linker(Set.of(SuggestionKind.TRANSFER), () -> {
            throw new IllegalStateException("boom");
        });
        AutoLinker fine = linker(Set.of(SuggestionKind.REFUND), () -> outcome(SuggestionKind.REFUND, 0, 1));

        AutoLinkService.RunResult result = service(broken, fine).run(userId);

        assertThat(result.suggested()).isEqualTo(1);
    }

    @Test
    void aRunThatLinkedSomethingWritesOneFeedNotification() {
        AutoLinker linker = linker(Set.of(SuggestionKind.TRANSFER), () -> {
            Map<SuggestionKind, Counts> m = new EnumMap<>(SuggestionKind.class);
            m.put(SuggestionKind.TRANSFER, new Counts(3, 0));
            m.put(SuggestionKind.CC_PAYMENT, new Counts(1, 0));
            return AutoLinkOutcome.of(m);
        });

        service(linker).run(userId);

        verify(feed, times(1)).record(eq(userId), eq(AppNotificationKind.AUTO_LINKED),
                eq("Linked 4 automatically: 3 transfers, 1 card payment"), isNull(), eq(AppNotificationRefs.NONE), anyString());
    }

    @Test
    void aRunThatOnlySuggestedWritesNoNotification() {
        service(linker(Set.of(SuggestionKind.REFUND), () -> outcome(SuggestionKind.REFUND, 0, 2))).run(userId);

        verify(feed, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRequestDuringARunSetsTheRerunFlagAndRunsOnceMore() {
        AtomicInteger passes = new AtomicInteger();
        AutoLinkService[] holder = new AutoLinkService[1];
        AutoLinker linker = linker(Set.of(SuggestionKind.TRANSFER), () -> {
            if (passes.incrementAndGet() == 1) {
                // Requested while the first pass is going: returns at once, flags a rerun.
                assertThat(holder[0].run(userId)).isEqualTo(AutoLinkService.RunResult.EMPTY);
                holder[0].run(userId);
            }
            return outcome(SuggestionKind.TRANSFER, 0, 0);
        });
        holder[0] = service(linker);

        holder[0].run(userId);

        assertThat(passes.get()).isEqualTo(2);
        // The user is free again afterwards.
        holder[0].run(userId);
        assertThat(passes.get()).isEqualTo(3);
    }

    @Test
    void backfillSummaryIsOneNotificationAndSkippedWhenNothingHappened() {
        AutoLinkService service = service();

        service.notifyBackfilled(userId, AutoLinkService.RunResult.EMPTY);
        verify(feed, never()).record(any(), any(), any(), any(), any(), any());

        Map<SuggestionKind, Counts> m = new EnumMap<>(SuggestionKind.class);
        m.put(SuggestionKind.TRANSFER, new Counts(12, 2));
        service.notifyBackfilled(userId, AutoLinkService.RunResult.ranWith(m));
        verify(feed, times(1)).record(eq(userId), eq(AppNotificationKind.AUTO_LINKED),
                eq("Checked your history: linked 12 automatically (12 transfers), 2 to review"), isNull(),
                eq(AppNotificationRefs.NONE), eq("auto-linked-backfill:" + userId));
    }
}
