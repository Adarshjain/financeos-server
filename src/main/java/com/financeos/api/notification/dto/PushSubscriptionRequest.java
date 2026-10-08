package com.financeos.api.notification.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.lang.Nullable;

/** The browser's {@code PushSubscription.toJSON()} plus an optional user-agent label. */
public record PushSubscriptionRequest(
        @NotBlank String endpoint,
        @NotNull @Valid Keys keys,
        @Nullable String userAgent) {

    public record Keys(@NotBlank String p256dh, @NotBlank String auth) {
    }
}
