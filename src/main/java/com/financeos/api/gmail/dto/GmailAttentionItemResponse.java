package com.financeos.api.gmail.dto;

import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedStatus;

import java.time.Instant;
import java.util.UUID;

public record GmailAttentionItemResponse(
    UUID id,
    String gmailMessageId,
    Instant internalDate,
    String senderAddress,
    String subject,
    GmailProcessedStatus status,
    String extractedLast4,
    String error,
    int attemptCount,
    Instant nextRetryAt,
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
