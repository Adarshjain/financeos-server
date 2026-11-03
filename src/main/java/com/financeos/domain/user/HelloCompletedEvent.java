package com.financeos.domain.user;

import java.util.UUID;

/** Published once, after the user's Hello screen was first completed (onboarding done). */
public record HelloCompletedEvent(UUID userId) {
}
