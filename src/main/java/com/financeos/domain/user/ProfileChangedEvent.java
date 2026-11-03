package com.financeos.domain.user;

import java.util.UUID;

/** Published after the user's name or birth date changed (statement passwords are built from them). */
public record ProfileChangedEvent(UUID userId, boolean nameChanged, boolean birthDateChanged) {
}
