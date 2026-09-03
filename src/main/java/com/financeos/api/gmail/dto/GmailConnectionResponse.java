package com.financeos.api.gmail.dto;

import com.financeos.gmail.domain.GmailConnection;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.UUID;

public record GmailConnectionResponse(
    UUID id,
    String email,
    boolean isConnected,
    boolean isPrimary,
    @Nullable Instant connectedAt,
    @Nullable Instant lastSyncedAt
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
