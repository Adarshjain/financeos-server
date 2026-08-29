package com.financeos.core.security;

import com.financeos.core.exception.TooManyAttemptsException;
import com.financeos.core.observability.Events;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AccountDeletionLimiter {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionLimiter.class);

    private static final int MAX_FAILURES = 5;
    private static final int LOCKOUT_MINUTES = 15;

    private final Map<UUID, Deque<Instant>> failureTimestamps = new ConcurrentHashMap<>();

    public synchronized void assertNotLockedOut(UUID userId) {
        if (userId == null) return;
        Deque<Instant> deque = failureTimestamps.get(userId);
        if (deque == null) return;

        evictExpired(deque);
        if (deque.size() >= MAX_FAILURES) {
            Instant oldest = deque.peekFirst();
            long lockoutSeconds = LOCKOUT_MINUTES * 60L;
            Instant expiresAt = oldest != null ? oldest.plusSeconds(lockoutSeconds) : Instant.now();
            long retryAfterSeconds = Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
            log.warn("Account deletion throttled: userId={}", userId,
                    StructuredArguments.keyValue("event", Events.ACCOUNT_DELETE_REJECTED),
                    StructuredArguments.keyValue("userId", userId),
                    StructuredArguments.keyValue("reason", "rate-limited"));
            throw new TooManyAttemptsException("Too many failed confirmation attempts. Try again later.", retryAfterSeconds);
        }
    }

    public synchronized void recordFailure(UUID userId) {
        if (userId == null) return;
        Deque<Instant> deque = failureTimestamps.computeIfAbsent(userId, k -> new ArrayDeque<>());
        evictExpired(deque);
        deque.addLast(Instant.now());
    }

    public synchronized void reset(UUID userId) {
        if (userId != null) {
            failureTimestamps.remove(userId);
        }
    }

    public synchronized void clear() {
        failureTimestamps.clear();
    }

    private void evictExpired(Deque<Instant> deque) {
        Instant cutoff = Instant.now().minusSeconds(LOCKOUT_MINUTES * 60L);
        while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
            deque.removeFirst();
        }
    }
}
