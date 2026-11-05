package com.financeos.domain.job;

import java.time.Instant;
import java.util.UUID;

/** One failed job for the admin list: every column except the stack (error_detail) and the payload, plus the owner's email. */
public record FailedJobRow(
        UUID id,
        JobType type,
        UUID userId,
        String userEmail,
        String errorCode,
        String errorMessage,
        int attempt,
        Instant startedAt,
        Instant finishedAt,
        Instant dismissedAt,
        String dismissedBy,
        String dismissReason) {}
