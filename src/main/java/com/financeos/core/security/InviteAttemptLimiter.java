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

/**
 * Global in-memory sliding-window limiter for failed invite code validation attempts.
 * <p>
 * Trade-off design decisions:
 * <ul>
 *   <li><b>Global (not per-IP):</b> Browser signups arrive via the Next.js server action (Vercel egress IP),
 *   so all users share a single source IP. Direct API attackers can rotate IPs freely. A global budget is the
 *   control that actually bites. An attacker can keep signup locked (10 bad guesses every 15 min), but signup
 *   is not a hot path and signups fail closed by design.</li>
 *   <li><b>In-memory:</b> Single instance — counter resets on server restart and there is no cluster coordination.</li>
 * </ul>
 */
@Component
public class InviteAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(InviteAttemptLimiter.class);

    private final AppConfigProperties appConfigProperties;
    private final Deque<Instant> failureTimestamps = new ArrayDeque<>();

    public InviteAttemptLimiter(AppConfigProperties appConfigProperties) {
        this.appConfigProperties = appConfigProperties;
    }

    public synchronized void assertNotLockedOut() {
        evictExpired();
        int maxFailures = appConfigProperties.getInvite().getMaxFailures();
        if (failureTimestamps.size() >= maxFailures) {
            Instant oldest = failureTimestamps.peekFirst();
            long lockoutSeconds = appConfigProperties.getInvite().getLockoutMinutes() * 60L;
            Instant expiresAt = oldest != null ? oldest.plusSeconds(lockoutSeconds) : Instant.now();
            long retryAfterSeconds = Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
            log.warn("Signup throttled: reason=rate-limited",
                    StructuredArguments.keyValue("event", Events.AUTH_SIGNUP_THROTTLED),
                    StructuredArguments.keyValue("reason", "rate-limited"));
            throw new TooManyAttemptsException("Too many attempts. Try again later.", retryAfterSeconds);
        }
    }

    public synchronized void recordFailure() {
        evictExpired();
        failureTimestamps.addLast(Instant.now());
    }

    public synchronized void reset() {
        failureTimestamps.clear();
    }

    synchronized void clear() {
        failureTimestamps.clear();
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(appConfigProperties.getInvite().getLockoutMinutes() * 60L);
        while (!failureTimestamps.isEmpty() && failureTimestamps.peekFirst().isBefore(cutoff)) {
            failureTimestamps.removeFirst();
        }
    }
}
