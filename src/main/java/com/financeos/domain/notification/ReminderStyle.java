package com.financeos.domain.notification;

/**
 * How loudly card-bill and EMI reminders push. The feed row is the same either way (it updates in place).
 * STANDARD pushes at every reminder offset and nags while overdue; QUIET pushes once per bill/EMI (at the
 * 1-day offset, or on the due day when that has already passed) and never re-nags.
 */
public enum ReminderStyle {
    STANDARD,
    QUIET;

    /** The style of a stored value; anything unknown or missing is STANDARD. */
    public static ReminderStyle parse(String value) {
        if (value == null) {
            return STANDARD;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }
}
