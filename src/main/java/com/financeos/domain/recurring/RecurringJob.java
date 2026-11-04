package com.financeos.domain.recurring;

import com.financeos.core.security.UserContextHelper;
import com.financeos.domain.transaction.autolink.TransactionsIngestedEvent;
import com.financeos.domain.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Daily run (07:30 IST, apart from the Gmail ingestion cron) that follows tracked series and then looks for
 * new ones, per user. Tracked series are also followed right after an ingest ({@link TransactionsIngestedEvent},
 * published after statement imports, Gmail batches and manual entry) so a new payment links without waiting
 * for the next day; detecting new series stays daily. Work runs on {@code jobExecutor}.
 */
@Component
public class RecurringJob {

    private static final Logger log = LoggerFactory.getLogger(RecurringJob.class);

    private final UserRepository userRepository;
    private final RecurringService recurringService;
    private final Executor executor;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RecurringJob(UserRepository userRepository, RecurringService recurringService,
                        @Qualifier("jobExecutor") Executor executor,
                        @Value("${autolink.enabled:true}") boolean enabled) {
        this.userRepository = userRepository;
        this.recurringService = recurringService;
        this.executor = executor;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${recurring.cron:0 30 7 * * *}", zone = "${recurring.zone:Asia/Kolkata}")
    public void daily() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runAll();
                } finally {
                    running.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            running.set(false);
            log.info("Recurring detection skipped: executor queue is full");
        }
    }

    /** Every user, one at a time; one user's failure does not stop the rest. */
    public void runAll() {
        List<UUID> ids = userRepository.findAllIds();
        for (UUID userId : ids) {
            runUser(userId, true);
        }
    }

    public void runUser(UUID userId, boolean detect) {
        try {
            UserContextHelper.runAs(userId, () -> {
                recurringService.linkAndAdvance(userId);
                if (detect) {
                    recurringService.detect(userId);
                }
            });
        } catch (Exception e) {
            log.warn("Recurring run failed for user {}: {}", userId, e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTransactionsIngested(TransactionsIngestedEvent event) {
        UUID userId = event.userId();
        if (!enabled || userId == null) {
            return;
        }
        try {
            executor.execute(() -> runUser(userId, false));
        } catch (RejectedExecutionException e) {
            log.info("Recurring link queue is full; skipped a run for user {}", userId);
        }
    }
}
