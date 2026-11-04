package com.financeos.domain.notification.reminder;

import java.util.UUID;

/** A bill was paid or an EMI payment recorded: the feed reminder with this key is done. Consumed after commit. */
public record ReminderResolveEvent(UUID userId, String dedupeKey) {
}
