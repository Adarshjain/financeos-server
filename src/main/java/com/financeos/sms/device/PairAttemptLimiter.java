package com.financeos.sms.device;

import com.financeos.core.exception.TooManyAttemptsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Global in-memory sliding window over failed pairing attempts (the endpoint is unauthenticated, so there is no user
 * to key on). Same trade-off as the invite limiter: single instance, resets on restart.
 */
@Component
public class PairAttemptLimiter {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Deque<Instant> failures = new ArrayDeque<>();

    public synchronized void assertNotLockedOut() {
        evict();
        if (failures.size() >= MAX_FAILURES) {
            long retry = Math.max(1, Duration.between(Instant.now(), failures.peekFirst().plus(WINDOW)).getSeconds());
            throw new TooManyAttemptsException("Too many attempts. Try again later.", retry);
        }
    }

    public synchronized void recordFailure() {
        evict();
        failures.addLast(Instant.now());
    }

    public synchronized void clear() {
        failures.clear();
    }

    private void evict() {
        Instant cutoff = Instant.now().minus(WINDOW);
        while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
            failures.removeFirst();
        }
    }
}
