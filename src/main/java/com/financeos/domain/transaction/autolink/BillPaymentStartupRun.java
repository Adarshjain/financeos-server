package com.financeos.domain.transaction.autolink;

import com.financeos.domain.user.UserRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link BillPaymentMatcher} once for every user shortly after the worker (or single node) starts, so the
 * bills already open when the feature shipped get matched without waiting for new activity. Idempotent: a bill
 * paid or matched already is skipped. Scheduling is off on the api role, so only the worker runs it; gated by the
 * same switch as {@link AutoLinkBackfill}. Fires once per JVM.
 */
@Component
public class BillPaymentStartupRun {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentStartupRun.class);

    private final BillPaymentMatcher matcher;
    private final UserRepository userRepository;
    private final Executor executor;
    private final boolean enabled;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public BillPaymentStartupRun(BillPaymentMatcher matcher, UserRepository userRepository,
                                 @Qualifier("jobExecutor") Executor executor,
                                 @Value("${autolink.backfill.enabled:true}") boolean enabled) {
        this.matcher = matcher;
        this.userRepository = userRepository;
        this.executor = executor;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${billpayment.startup.initial-delay:PT45S}", fixedDelayString = "${billpayment.startup.delay:PT8760H}")
    public void tick() {
        if (!enabled || !started.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::runAll);
        } catch (RejectedExecutionException e) {
            started.set(false);
            log.info("Bill payment start-up run skipped: executor queue is full");
        }
    }

    /** Runs the matcher for every user; returns how many it linked in total. A user that fails is logged and skipped. */
    public int runAll() {
        List<UUID> users = userRepository.findAllIds();
        int linked = 0;
        for (UUID userId : users) {
            try {
                linked += matcher.runForUser(userId).linked();
            } catch (Exception e) {
                log.warn("Bill payment start-up run failed for user {}: {}", userId, e.getMessage());
            }
        }
        log.info("Bill payment start-up run: {} user(s), {} payment(s) matched", users.size(), linked);
        return linked;
    }
}
