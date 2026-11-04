package com.financeos.api.gmail.dto;

import com.financeos.gmail.classify.BankSenderDomains;
import com.financeos.gmail.domain.AttentionReason;
import com.financeos.gmail.domain.GmailAttentionRow;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedStatus;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

public record GmailAttentionItemResponse(
    UUID id,
    String gmailMessageId,
    @Nullable Instant internalDate,
    @Nullable String senderAddress,
    @Nullable String subject,
    GmailProcessedStatus status,
    @Nullable String extractedLast4,
    @Nullable String error,
    int attemptCount,
    @Nullable Instant nextRetryAt,
    Instant discoveredAt,
    /** When this item was part of a "needs attention" push digest; null until then. */
    @Nullable Instant attentionNotifiedAt,
    /** When the message was last processed (the attempt that parked or failed it). */
    @Nullable Instant processedAt,
    /** The Gmail mailbox the message came from. */
    String connectionEmail,
    /** The account the message resolved to (set for "not opted in"); null when no account matched. */
    @Nullable UUID accountId,
    @Nullable String accountName,
    /** When the user archived this item; null while it is active. */
    @Nullable Instant archivedAt,
    /** Why the item needs attention, derived from its status and error. */
    AttentionReason reason,
    /** Catalog bank id for the sender's domain (for "Create account"); null when unknown. */
    @Nullable String suggestedBankId,
    /** The card or account last four extracted from the mail, when one was stored; null otherwise. */
    @Nullable String suggestedLast4
) {
    public static GmailAttentionItemResponse from(GmailAttentionRow row) {
        GmailProcessedMessage gpm = row.message();
        return new GmailAttentionItemResponse(
            gpm.getId(),
            gpm.getGmailMessageId(),
            gpm.getInternalDate(),
            gpm.getSenderAddress(),
            gpm.getSubject(),
            gpm.getStatus(),
            gpm.getExtractedLast4(),
            gpm.getError(),
            gpm.getAttemptCount(),
            gpm.getNextRetryAt(),
            gpm.getDiscoveredAt(),
            gpm.getAttentionNotifiedAt(),
            gpm.getProcessedAt(),
            row.connectionEmail(),
            row.accountId(),
            row.accountName(),
            gpm.getArchivedAt(),
            AttentionReason.of(gpm.getStatus(), gpm.getError()),
            BankSenderDomains.current().bankIdFor(gpm.getSenderAddress()),
            gpm.getExtractedLast4() == null || gpm.getExtractedLast4().isBlank() ? null : gpm.getExtractedLast4()
        );
    }
}
