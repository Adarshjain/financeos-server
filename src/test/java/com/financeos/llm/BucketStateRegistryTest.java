package com.financeos.llm;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BucketStateRegistryTest {

    @Test
    public void testPerDay429CooldownUntilMidnightPt() {
        // Fixed instant: 2026-08-24 10:00:00 UTC (3:00 AM PT)
        Instant fixedInstant = Instant.parse("2026-08-24T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));

        BucketStateRegistry registry = new BucketStateRegistry(fixedClock);
        String bucketKey = "key-1:gemini-3.7-flash";

        assertFalse(registry.isCooldown(bucketKey));

        String body = "{\"error\": {\"code\": 429, \"message\": \"RESOURCE_EXHAUSTED\", \"details\": [{\"reason\": \"RATE_LIMIT_EXCEEDED\", \"quotaId\": \"PerDay\"}]}}";
        registry.handle429(bucketKey, "gemini", "gemini-3.7-flash", body, null, null);

        assertTrue(registry.isCooldown(bucketKey));

        Instant expiry = registry.getSoonestCooldownExpiry(List.of(bucketKey));
        assertNotNull(expiry);

        // midnight PT on 2026-08-25 is 2026-08-25T07:00:00Z (PDT is UTC-7)
        ZonedDateTime expiryPt = ZonedDateTime.ofInstant(expiry, ZoneId.of("America/Los_Angeles"));
        assertEquals(0, expiryPt.getHour());
        assertEquals(0, expiryPt.getMinute());
        assertEquals(25, expiryPt.getDayOfMonth());
    }

    @Test
    public void testPerMinute429Cooldown() {
        Instant fixedInstant = Instant.parse("2026-08-24T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));

        BucketStateRegistry registry = new BucketStateRegistry(fixedClock);
        String bucketKey = "key-1:cerebras";

        registry.handle429(bucketKey, "cerebras", "llama3.1", "Rate limit exceeded", 120L, null);

        assertTrue(registry.isCooldown(bucketKey));

        Instant expiry = registry.getSoonestCooldownExpiry(List.of(bucketKey));
        assertNotNull(expiry);
        assertEquals(fixedInstant.plusSeconds(120), expiry);
    }

    @Test
    public void testCircuitBreaker3Failures() {
        Instant fixedInstant = Instant.parse("2026-08-24T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));

        BucketStateRegistry registry = new BucketStateRegistry(fixedClock);
        String bucketKey = "key-1:groq";

        assertFalse(registry.isCooldown(bucketKey));
        registry.recordFailure(bucketKey, "groq", null);
        assertFalse(registry.isCooldown(bucketKey));
        registry.recordFailure(bucketKey, "groq", null);
        assertFalse(registry.isCooldown(bucketKey));
        registry.recordFailure(bucketKey, "groq", null);
        assertTrue(registry.isCooldown(bucketKey));

        registry.recordSuccess(bucketKey, "groq");
        assertFalse(registry.isCooldown(bucketKey));
    }
}
