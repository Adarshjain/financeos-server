package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.notification.feed.NotificationFeedService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AutoLinkNightlyTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 10);

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final List<LinkScope> passes = new ArrayList<>();
    private boolean failNext;

    /** A real service over one recording linker; {@code failNext} makes the next pass throw. */
    private AutoLinkService service() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        AutoLinker linker = new AutoLinker() {
            @Override
            public Set<SuggestionKind> ownedKinds() {
                return Set.of();
            }

            @Override
            public AutoLinkOutcome link(UUID id) {
                return link(id, LinkScope.ALL);
            }

            @Override
            public AutoLinkOutcome link(UUID id, LinkScope scope) {
                passes.add(scope);
                if (failNext) {
                    failNext = false;
                    throw new IllegalStateException("boom");
                }
                return AutoLinkOutcome.of(Map.of());
            }
        };
        return new AutoLinkService(List.of(linker), tm, mock(NotificationFeedService.class));
    }

    @Test
    void everyUserWithAScopedRunIsRunInFullByTheNightlyPassAndThenCleared() {
        AutoLinkService service = service();
        service.run(a, new LinkScope(D, D));
        service.run(b, new LinkScope(D, D.plusDays(1)));
        assertThat(service.touchedUsers()).containsExactlyInAnyOrder(a, b);
        passes.clear();

        int clean = new AutoLinkNightly(service, Runnable::run, true).runTouched();

        assertThat(clean).isEqualTo(2);
        assertThat(passes).containsExactly(LinkScope.ALL, LinkScope.ALL);
        assertThat(service.touchedUsers()).isEmpty();
    }

    @Test
    void aFullRunAloneDoesNotMarkAUserAndANightlyPassWithNobodyDoesNothing() {
        AutoLinkService service = service();
        service.run(a);
        passes.clear();

        assertThat(service.touchedUsers()).isEmpty();
        assertThat(new AutoLinkNightly(service, Runnable::run, true).runTouched()).isZero();
        assertThat(passes).isEmpty();
    }

    @Test
    void aFailedScopedRunStaysMarkedAndAFailedNightlyPassKeepsTheUserForTheNextNight() {
        AutoLinkService service = service();
        failNext = true;
        service.run(a, new LinkScope(D, D));
        assertThat(service.touchedUsers()).containsExactly(a);

        failNext = true;
        assertThat(new AutoLinkNightly(service, Runnable::run, true).runTouched()).isZero();
        assertThat(service.touchedUsers()).containsExactly(a);

        assertThat(new AutoLinkNightly(service, Runnable::run, true).runTouched()).isEqualTo(1);
        assertThat(service.touchedUsers()).isEmpty();
    }

    @Test
    void markTouchedAddsADroppedUserForTheNightlyPass() {
        AutoLinkService service = service();
        service.markTouched(b);

        assertThat(new AutoLinkNightly(service, Runnable::run, true).runTouched()).isEqualTo(1);
        assertThat(passes).containsExactly(LinkScope.ALL);
        assertThat(service.touchedUsers()).isEmpty();
    }

    @Test
    void theTickDoesNothingWhenAutoLinkIsOff() {
        AutoLinkService service = mock(AutoLinkService.class);
        List<Runnable> queue = new ArrayList<>();

        new AutoLinkNightly(service, queue::add, false).tick();

        assertThat(queue).isEmpty();
        verify(service, never()).touchedUsers();
    }

    @Test
    void theTickHandsTheWorkToTheExecutorAndSurvivesAFullQueue() {
        AutoLinkService service = mock(AutoLinkService.class);
        when(service.touchedUsers()).thenReturn(Set.of());
        List<Runnable> queue = new ArrayList<>();
        new AutoLinkNightly(service, queue::add, true).tick();
        assertThat(queue).hasSize(1);
        queue.get(0).run();
        verify(service).touchedUsers();

        new AutoLinkNightly(service, r -> { throw new RejectedExecutionException(); }, true).tick();   // no exception
    }
}
