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
    @Nullable Instant lastSyncedAt,
    /** When Google rejected the stored refresh token; null while the token works. */
    @Nullable Instant authFailedAt,
    /** The user still wants this mailbox but its token is dead: show a Reconnect action. */
    boolean needsReconnect
) {
    public static GmailConnectionResponse from(GmailConnection connection, Instant lastSyncedAt) {
        boolean connected = Boolean.TRUE.equals(connection.getIsConnected());
        return new GmailConnectionResponse(
            connection.getId(),
            connection.getEmail(),
            connected,
            Boolean.TRUE.equals(connection.getIsPrimary()),
            connection.getConnectedAt(),
            lastSyncedAt,
            connection.getAuthFailedAt(),
            connected && connection.getAuthFailedAt() != null
        );
    }
}
