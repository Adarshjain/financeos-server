package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionsIngestedListenerTest {

    private final AutoLinkService service = mock(AutoLinkService.class);
    private final List<Runnable> queue = new ArrayList<>();
    private final TransactionsIngestedListener listener = new TransactionsIngestedListener(service, queue::add);
    private final UUID userId = UUID.randomUUID();

    @Test
    void aBurstOfEventsQueuesOneRun() {
        for (int i = 0; i < 5; i++) {
            listener.onTransactionsIngested(new TransactionsIngestedEvent(userId));
        }

        assertThat(queue).hasSize(1);
        queue.get(0).run();
        verify(service, times(1)).run(userId);
    }

    @Test
    void anEventDuringARunOnlyFlagsAnotherRun() {
        when(service.requestRerunIfRunning(userId)).thenReturn(true);

        listener.onTransactionsIngested(new TransactionsIngestedEvent(userId));

        assertThat(queue).isEmpty();
    }

    @Test
    void afterTheQueuedRunStartsANewEventQueuesAgain() {
        listener.onTransactionsIngested(new TransactionsIngestedEvent(userId));
        queue.get(0).run();
        listener.onTransactionsIngested(new TransactionsIngestedEvent(userId));

        assertThat(queue).hasSize(2);
    }
}
