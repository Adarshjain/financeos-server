package com.financeos.domain.notification;

/**
 * The user-facing on/off switches. Each producer maps its concrete messages onto one of these;
 * new producers (Gmail reconnect, loan EMIs, …) add a value here and a default below.
 */
public enum NotificationKind {
    /** A new credit-card statement landed: the digest. */
    STATEMENT_RECEIVED,
    /** "Due in N days" / "due today" reminders. */
    BILL_DUE_REMINDER,
    /** Daily nag after the due date until marked paid. */
    BILL_OVERDUE;

    public boolean defaultEnabled() {
        return true;
    }
}
