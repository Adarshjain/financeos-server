package com.financeos.api.llm.dto;

import org.springframework.lang.Nullable;

import java.time.Instant;

public record LlmBucketHealthDto(
        String provider,
        String providerName,
        String model,
        String modelLabel,
        String keyLast4,
        @Nullable String keyLabel,
        boolean inCooldown,
        @Nullable Instant cooldownUntil,
        int consecutiveFailures
) {}
