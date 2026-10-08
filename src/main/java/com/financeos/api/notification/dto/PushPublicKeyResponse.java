package com.financeos.api.notification.dto;

/** {@code publicKey} is the base64url VAPID key for {@code PushManager.subscribe}; empty when not configured. */
public record PushPublicKeyResponse(String publicKey, boolean configured) {
}
