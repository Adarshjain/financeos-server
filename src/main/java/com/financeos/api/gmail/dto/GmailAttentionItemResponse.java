package com.financeos.api.gmail.dto;

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
    Instant discoveredAt
) {
    public static GmailAttentionItemResponse from(GmailProcessedMessage gpm) {
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
            gpm.getDiscoveredAt()
        );
    }
}
