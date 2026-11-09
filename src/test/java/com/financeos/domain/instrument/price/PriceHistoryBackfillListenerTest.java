package com.financeos.domain.instrument.price;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** An event published outside any transaction (the daily catalog sync) still reaches the listener. */
class PriceHistoryBackfillListenerTest {

    @Test
    void eventPublishedOutsideATransactionIsHandled() {
        PriceHistoryService history = mock(PriceHistoryService.class);
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(TransactionalEventListenerFactory.class);
            ctx.registerBean(PriceHistoryService.class, () -> history);
            ctx.registerBean(PriceProperties.class, PriceProperties::new);
            ctx.registerBean(PriceHistoryBackfillListener.class);
            ctx.refresh();
            Set<UUID> ids = Set.of(UUID.randomUUID());
            ctx.publishEvent(new PriceRefreshEvent(ids));
            verify(history, timeout(2000)).backfill(ids);
        }
    }
}
