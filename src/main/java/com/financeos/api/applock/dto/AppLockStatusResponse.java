package com.financeos.api.applock.dto;

import org.springframework.lang.Nullable;

/** {@code lockAfterSeconds} is null when the session is not armed. */
public record AppLockStatusResponse(
        boolean armed,
        boolean locked,
        @Nullable Integer lockAfterSeconds,
        String rpId,
        int heartbeatSeconds) {
}
