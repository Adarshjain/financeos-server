package com.financeos.llm;

import com.financeos.core.observability.Events;
import com.financeos.core.observability.ObservabilityMetrics;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BucketStateRegistry {

    private static final Logger log = LoggerFactory.getLogger(BucketStateRegistry.class);
    private static final ZoneId LOS_ANGELES_ZONE = ZoneId.of("America/Los_Angeles");

    public static class BucketState {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        final AtomicLong lastFailureTimestamp = new AtomicLong(0);
        final AtomicLong cooldownUntil = new AtomicLong(0);
    }

    private final ConcurrentHashMap<String, BucketState> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public BucketStateRegistry(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public BucketStateRegistry() {
        this(Clock.systemUTC());
    }

    public boolean isCooldown(String bucketKey) {
        BucketState state = states.get(bucketKey);
        if (state == null) {
            return false;
        }
        long now = clock.millis();
        long cooldownUntil = state.cooldownUntil.get();
        if (cooldownUntil > 0 && now < cooldownUntil) {
            return true;
        }
        if (state.consecutiveFailures.get() >= 3) {
            long elapsed = now - state.lastFailureTimestamp.get();
            return elapsed < 60000L;
        }
        return false;
    }

    public void handle429(String bucketKey, String providerId, String model, String responseBody, Long retryAfterSeconds, ObservabilityMetrics metrics) {
        BucketState state = states.computeIfAbsent(bucketKey, k -> new BucketState());
        long now = clock.millis();
        long cooldownUntilMs;
        String reason;

        if (responseBody != null && (responseBody.contains("PerDay") || responseBody.contains("per_day") || (responseBody.contains("quotaId") && responseBody.contains("Day")))) {
            reason = "per-day";
            Instant midnightPt = ZonedDateTime.ofInstant(clock.instant(), LOS_ANGELES_ZONE)
                    .plusDays(1)
                    .truncatedTo(ChronoUnit.DAYS)
                    .toInstant();
            cooldownUntilMs = midnightPt.toEpochMilli();
        } else {
            reason = "per-minute";
            long secondsToWait = (retryAfterSeconds != null && retryAfterSeconds > 0) ? retryAfterSeconds : 60L;
            secondsToWait = Math.max(secondsToWait, 60L);
            cooldownUntilMs = now + (secondsToWait * 1000L);
        }

        state.cooldownUntil.set(cooldownUntilMs);
        state.lastFailureTimestamp.set(now);

        log.warn("LLM bucket cooldown: bucket={}, provider={}, model={}, reason={}, untilTs={}",
                bucketKey, providerId, model, reason, Instant.ofEpochMilli(cooldownUntilMs),
                StructuredArguments.keyValue("event", Events.LLM_BUCKET_COOLDOWN),
                StructuredArguments.keyValue("bucket", bucketKey),
                StructuredArguments.keyValue("provider", providerId),
                StructuredArguments.keyValue("model", model),
                StructuredArguments.keyValue("reason", reason),
                StructuredArguments.keyValue("cooldownUntil", cooldownUntilMs));

        if (metrics != null) {
            metrics.recordLlmAttempt(providerId, "cooldown_" + reason);
        }
    }

    public void recordFailure(String bucketKey, String providerId, ObservabilityMetrics metrics) {
        BucketState state = states.computeIfAbsent(bucketKey, k -> new BucketState());
        int failures = state.consecutiveFailures.incrementAndGet();
        state.lastFailureTimestamp.set(clock.millis());
        if (failures == 3) {
            log.warn("LLM circuit opened for bucket {}: 3 consecutive failures", bucketKey,
                    StructuredArguments.keyValue("event", Events.LLM_CIRCUIT_OPENED),
                    StructuredArguments.keyValue("bucket", bucketKey),
                    StructuredArguments.keyValue("provider", providerId));
        }
        if (metrics != null) {
            metrics.recordLlmAttempt(providerId, "failure");
        }
    }

    public void recordSuccess(String bucketKey, String providerId) {
        BucketState state = states.computeIfAbsent(bucketKey, k -> new BucketState());
        int previousFailures = state.consecutiveFailures.getAndSet(0);
        state.lastFailureTimestamp.set(0);
        state.cooldownUntil.set(0);
        if (previousFailures >= 3) {
            log.info("LLM circuit closed for bucket {}: provider={}", bucketKey, providerId,
                    StructuredArguments.keyValue("event", Events.LLM_CIRCUIT_CLOSED),
                    StructuredArguments.keyValue("bucket", bucketKey),
                    StructuredArguments.keyValue("provider", providerId));
        }
    }

    public Instant getSoonestCooldownExpiry(List<String> bucketKeys) {
        long now = clock.millis();
        long soonest = Long.MAX_VALUE;

        for (String bucketKey : bucketKeys) {
            BucketState state = states.get(bucketKey);
            if (state != null) {
                long until = state.cooldownUntil.get();
                if (until > now && until < soonest) {
                    soonest = until;
                }
            }
        }
        return soonest != Long.MAX_VALUE ? Instant.ofEpochMilli(soonest) : null;
    }
}
