package com.financeos.api.gmail.dto;

import com.financeos.gmail.domain.GmailConnection;
import java.time.Instant;
import java.util.UUID;

public record GmailConnectionResponse(
    UUID id,
    String email,
    boolean isConnected,
    boolean isPrimary,
    Instant connectedAt,
    Instant lastSyncedAt
) {
    public static GmailConnectionResponse from(GmailConnection connection, Instant lastSyncedAt) {
        return new GmailConnectionResponse(
            connection.getId(),
            connection.getEmail(),
            connection.getIsConnected(),
            connection.getIsPrimary(),
            connection.getConnectedAt(),
            lastSyncedAt
        );
    }
}
