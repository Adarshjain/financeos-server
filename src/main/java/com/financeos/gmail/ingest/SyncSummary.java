package com.financeos.gmail.ingest;

import com.financeos.gmail.domain.GmailProcessedStatus;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of one Gmail sync run, stored as the GMAIL_SYNC job result. The leading counters are the
 * original shape; everything after {@code backlogRemaining} is run detail so the user can see what
 * was searched and what happened to each email without reading server logs.
 */
public record SyncSummary(
    int discovered,
    int processed,
    int created,
    int reconciled,
    int skipped,
    int parked,
    int failedRetryable,
    int failedPermanent,
    long backlogRemaining,
    @Nullable String mailbox,
    // Distinct message ids Gmail returned across this run's listings, new or already known.
    int listed,
    // Earliest point the forward pass searched from (cursor minus overlap); null when nothing was listed.
    @Nullable Instant searchedSince,
    // Date the backfill pass listed down to; null when no backfill ran this run.
    @Nullable LocalDate backfilledTo,
    List<String> senders,
    // Most emails this run was allowed to process (steady or backlog budget).
    int budget,
    Map<GmailProcessedStatus, Integer> statusCounts,
    List<MessageResult> messages,
    int messagesTruncated
) {
    public static final int MAX_MESSAGES = 100;
    public static final int MAX_TRANSACTIONS_PER_MESSAGE = 5;

    public SyncSummary(int discovered, int processed, int created, int reconciled, int skipped, int parked, int failedRetryable, int failedPermanent, long backlogRemaining) {
        this(discovered, processed, created, reconciled, skipped, parked, failedRetryable, failedPermanent, backlogRemaining,
                null, 0, null, null, List.of(), 0, Map.of(), List.of(), 0);
    }

    public SyncSummary(int discovered, int processed, int created, int reconciled, int skipped, int parked, int failedRetryable, int failedPermanent) {
        this(discovered, processed, created, reconciled, skipped, parked, failedRetryable, failedPermanent, 0L);
    }

    /** What happened to one email in this run. */
    public record MessageResult(
        UUID ledgerId,
        String gmailMessageId,
        @Nullable Instant receivedAt,
        @Nullable String sender,
        @Nullable String subject,
        GmailProcessedStatus status,
        @Nullable UUID accountId,
        @Nullable String accountName,
        @Nullable String last4,
        @Nullable String attachmentFilename,
        @Nullable String error,
        int attemptCount,
        @Nullable Instant nextRetryAt,
        // Transactions this email created (a statement can create many); the list is capped.
        int createdCount,
        // Statement lines that matched transactions already recorded from alerts.
        int matchedCount,
        List<TxnRef> transactions
    ) {}

    public record TxnRef(
        UUID id,
        @Nullable LocalDate date,
        @Nullable BigDecimal amount,
        @Nullable String description,
        @Nullable String type
    ) {}
}
