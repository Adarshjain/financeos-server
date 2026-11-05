package com.financeos.api.admin.dto;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/** One failed job across users; {@code errorDetail} (the stack) is filled only on the detail endpoint. */
public record FailedJobResponse(
        UUID id,
        String type,
        String jobName,
        @Nullable UUID userId,
        @Nullable String userEmail,
        @Nullable String errorCode,
        @Nullable String error,
        int attempt,
        @Nullable Instant startedAt,
        @Nullable Instant finishedAt,
        /** False when the job needs stored files that have expired. */
        boolean retryable,
        @Nullable Instant dismissedAt,
        @Nullable String dismissedBy,
        @Nullable String dismissReason,
        @Nullable String errorDetail) {}
