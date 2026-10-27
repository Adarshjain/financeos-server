package com.financeos.domain.instrument.price;

import com.financeos.core.observability.MdcTaskDecorator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * When a trade or import commits and publishes {@link PriceRefreshEvent}, loads the touched
 * instruments' price history on a background thread so the request never waits for it. One worker
 * thread, so history fetches never stack up against Yahoo.
 */
@Component
public class PriceHistoryBackfillListener {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryBackfillListener.class);

    private final PriceHistoryService historyService;
    private final PriceProperties priceProperties;
    private final ThreadPoolTaskExecutor executor;

    public PriceHistoryBackfillListener(PriceHistoryService historyService, PriceProperties priceProperties) {
        this.historyService = historyService;
        this.priceProperties = priceProperties;
        this.executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("price-history-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPriceRefresh(PriceRefreshEvent event) {
        Set<UUID> ids = event.instrumentIds();
        if (!priceProperties.getHistory().isEnabled() || ids == null || ids.isEmpty()) {
            return;
        }
        Set<UUID> copy = Set.copyOf(ids);
        try {
            executor.execute(() -> historyService.backfill(copy));
        } catch (RejectedExecutionException e) {
            log.info("Price history backfill queue is full; skipped for {} instruments", copy.size());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
