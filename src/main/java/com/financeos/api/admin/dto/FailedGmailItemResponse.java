package com.financeos.api.admin.dto;

import com.financeos.gmail.domain.GmailProcessedStatus;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

/** One Gmail ledger row in a failed (or admin-skipped) state across users; {@code errorDetail} only on the detail endpoint. */
public record FailedGmailItemResponse(
        UUID ledgerId,
        String gmailMessageId,
        UUID userId,
        String userEmail,
        String connectionEmail,
        @Nullable String sender,
        @Nullable String subject,
        @Nullable Instant internalDate,
        GmailProcessedStatus status,
        int attemptCount,
        @Nullable Instant nextRetryAt,
        @Nullable String error,
        @Nullable Instant processedAt,
        @Nullable Instant dismissedAt,
        @Nullable String dismissedBy,
        @Nullable String dismissReason,
        @Nullable String errorDetail) {}
