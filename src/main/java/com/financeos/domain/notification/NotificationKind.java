package com.financeos.domain.notification;

/**
 * The user-facing on/off switches. Each producer maps its concrete messages onto one of these;
 * a new producer adds a value here (and a row to the client's switch list).
 */
public enum NotificationKind {
    /** A new credit-card statement landed: the digest. */
    STATEMENT_RECEIVED,
    /** "Due in N days" / "due today" card-bill reminders. */
    BILL_DUE_REMINDER,
    /** Daily nag after the card bill's due date until marked paid. */
    BILL_OVERDUE,
    /** A Gmail mailbox's token died: reconnect it. Once on detection, then weekly while still dead. */
    GMAIL_RECONNECT,
    /** Imported emails that need a hand: unmatched account, account not opted in, permanent failure. */
    GMAIL_ATTENTION,
    /** Loan EMI "debits in N days" / "debits today" reminders, at the same offsets as card bills. */
    EMI_DUE_REMINDER,
    /** An EMI's due date passed with no payment recorded: once, then weekly. */
    EMI_OVERDUE;

    public boolean defaultEnabled() {
        return true;
    }
}
