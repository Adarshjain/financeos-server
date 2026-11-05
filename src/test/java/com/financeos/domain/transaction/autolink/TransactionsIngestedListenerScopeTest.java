package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionsIngestedListenerScopeTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 10);

    private final AutoLinkService service = mock(AutoLinkService.class);
    private final List<Runnable> queue = new ArrayList<>();
    private final TransactionsIngestedListener listener = new TransactionsIngestedListener(service, queue::add);
    private final UUID userId = UUID.randomUUID();

    private TransactionsIngestedEvent event(LocalDate from, LocalDate to) {
        return new TransactionsIngestedEvent(userId, new LinkScope(from, to));
    }

    @Test
    void queuedEventsMergeTheirSpansIntoOneRun() {
        listener.onTransactionsIngested(event(D, D.plusDays(1)));
        listener.onTransactionsIngested(event(D.minusDays(4), D));
        listener.onTransactionsIngested(event(D.plusDays(3), D.plusDays(9)));

        assertThat(queue).hasSize(1);
        queue.get(0).run();
        verify(service).run(userId, new LinkScope(D.minusDays(4), D.plusDays(9)));
    }

    @Test
    void aFullScopeEventAmongTheQueuedOnesMakesTheRunFull() {
        listener.onTransactionsIngested(event(D, D));
        listener.onTransactionsIngested(new TransactionsIngestedEvent(userId));
        listener.onTransactionsIngested(event(D, D.plusDays(2)));

        assertThat(queue).hasSize(1);
        queue.get(0).run();
        verify(service).run(userId);
        verify(service, never()).run(any(), any(LinkScope.class));
    }

    @Test
    void aScopedEventDuringARunPassesItsScopeToTheRerunRequest() {
        LinkScope scope = new LinkScope(D, D.plusDays(1));
        when(service.requestRerunIfRunning(userId, scope)).thenReturn(true);

        listener.onTransactionsIngested(new TransactionsIngestedEvent(userId, scope));

        assertThat(queue).isEmpty();
        verify(service).requestRerunIfRunning(userId, scope);
    }

    @Test
    void aFullScopeEventDuringARunRequestsAFullRerun() {
        when(service.requestRerunIfRunning(userId)).thenReturn(true);

        listener.onTransactionsIngested(new TransactionsIngestedEvent(userId));

        assertThat(queue).isEmpty();
        verify(service).requestRerunIfRunning(userId);
    }

    @Test
    void aFullQueueRemarksTheUserForTheNightlyPassInsteadOfDroppingTheScopeSilently() {
        TransactionsIngestedListener full = new TransactionsIngestedListener(service, r -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });

        full.onTransactionsIngested(event(D, D));

        verify(service).markTouched(userId);
        // and the user is not stuck as pending: the next event tries to queue again
        full.onTransactionsIngested(event(D, D));
        verify(service, org.mockito.Mockito.times(2)).markTouched(userId);
    }

    @Test
    void eventsAfterTheQueuedRunStartedQueueAFreshScope() {
        listener.onTransactionsIngested(event(D, D));
        queue.get(0).run();
        listener.onTransactionsIngested(event(D.plusDays(30), D.plusDays(31)));
        queue.get(1).run();

        verify(service).run(userId, new LinkScope(D, D));
        verify(service).run(userId, new LinkScope(D.plusDays(30), D.plusDays(31)));
    }
}
