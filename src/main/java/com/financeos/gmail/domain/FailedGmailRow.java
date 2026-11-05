package com.financeos.gmail.domain;

import java.time.Instant;
import java.util.UUID;

/** One failed Gmail ledger row for the admin list: every column except the stack (error_detail), plus the owner and mailbox emails. */
public record FailedGmailRow(
        UUID id,
        String gmailMessageId,
        UUID userId,
        String userEmail,
        String connectionEmail,
        String senderAddress,
        String subject,
        Instant internalDate,
        GmailProcessedStatus status,
        int attemptCount,
        Instant nextRetryAt,
        String error,
        Instant processedAt,
        Instant dismissedAt,
        String dismissedBy,
        String dismissReason) {}
