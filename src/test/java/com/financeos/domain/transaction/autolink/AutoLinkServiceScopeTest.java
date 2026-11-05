package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.domain.notification.feed.NotificationFeedService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AutoLinkServiceScopeTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 10);

    private final UUID userId = UUID.randomUUID();
    /** The scope each pass was given; null for a pass through the full-history link(userId). */
    private final List<LinkScope> passes = new ArrayList<>();
    private AutoLinkService service;

    private void service(Consumer<Integer> duringPass) {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        AutoLinker linker = new AutoLinker() {
            @Override
            public Set<SuggestionKind> ownedKinds() {
                return Set.of();
            }

            @Override
            public AutoLinkOutcome link(UUID id) {
                passes.add(LinkScope.ALL);
                duringPass.accept(passes.size());
                return AutoLinkOutcome.of(java.util.Map.of());
            }

            @Override
            public AutoLinkOutcome link(UUID id, LinkScope scope) {
                passes.add(scope);
                duringPass.accept(passes.size());
                return AutoLinkOutcome.of(java.util.Map.of());
            }
        };
        service = new AutoLinkService(List.of(linker), tm, mock(NotificationFeedService.class));
    }

    @Test
    void aScopedRunPassesItsScopeToTheLinkersAndAFullRunUsesTheFullPass() {
        service(n -> { });
        LinkScope scope = new LinkScope(D, D.plusDays(2));

        service.run(userId, scope);
        service.run(userId);

        assertThat(passes).containsExactly(scope, LinkScope.ALL);
    }

    @Test
    void requestsDuringARunAreMergedIntoOneRerunOverTheirUnion() {
        LinkScope first = new LinkScope(D, D);
        LinkScope second = new LinkScope(D.minusDays(3), D.minusDays(2));
        LinkScope third = new LinkScope(D.plusDays(5), D.plusDays(6));
        service(n -> {
            if (n == 1) {
                service.run(userId, second);                       // flags a rerun
                service.requestRerunIfRunning(userId, third);      // merged into it
            }
        });

        service.run(userId, first);

        assertThat(passes).containsExactly(first, new LinkScope(D.minusDays(3), D.plusDays(6)));
    }

    @Test
    void aFullRequestDuringARunMakesTheRerunFull() {
        service(n -> {
            if (n == 1) {
                service.requestRerunIfRunning(userId, new LinkScope(D, D));
                service.requestRerunIfRunning(userId);
            }
        });

        service.run(userId, new LinkScope(D, D));

        assertThat(passes).containsExactly(new LinkScope(D, D), LinkScope.ALL);
    }

    @Test
    void theRerunScopeIsClearedAfterItRuns() {
        service(n -> {
            if (n == 1) {
                service.requestRerunIfRunning(userId);
            }
        });

        service.run(userId, new LinkScope(D, D));
        service.run(userId, new LinkScope(D.plusDays(9), D.plusDays(9)));

        assertThat(passes).containsExactly(new LinkScope(D, D), LinkScope.ALL, new LinkScope(D.plusDays(9), D.plusDays(9)));
    }
}
