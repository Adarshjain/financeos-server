package com.financeos.domain.notification.reminder;

import java.util.UUID;

/**
 * A bill was paid, an EMI payment recorded or a loan closed: the feed reminder with this key (or, when
 * {@code prefix}, every reminder whose key starts with it) is done. Consumed after commit.
 */
public record ReminderResolveEvent(UUID userId, String dedupeKey, boolean prefix) {

    public ReminderResolveEvent(UUID userId, String dedupeKey) {
        this(userId, dedupeKey, false);
    }

    /** Resolves every EMI reminder of the loan. */
    public static ReminderResolveEvent allEmisOf(UUID userId, UUID loanId) {
        return new ReminderResolveEvent(userId, ReminderKeys.emiPrefix(loanId), true);
    }
}
