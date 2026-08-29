package com.financeos.api.llm.dto;

import java.time.Instant;

public record LlmBucketHealthDto(
        String provider,
        String providerName,
        String model,
        String modelLabel,
        String keyLast4,
        String keyLabel,
        boolean inCooldown,
        Instant cooldownUntil,
        int consecutiveFailures
) {}
