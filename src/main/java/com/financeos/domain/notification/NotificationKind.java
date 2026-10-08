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
    /** A card's next statement is overdue by the projected closing day plus a grace period. */
    STATEMENT_EXPECTED,
    /** A parsed statement left unreconciled or duplicate-suspect transactions in its period. */
    STATEMENT_REVIEW_DIGEST,
    /** A Gmail mailbox's token died: reconnect it. Once on detection, then weekly while still dead. */
    GMAIL_RECONNECT,
    /** Imported emails that need a hand: unmatched account, account not opted in, permanent failure. */
    GMAIL_ATTENTION,
    /** Loan EMI "debits in N days" / "debits today" reminders, at the same offsets as card bills. */
    EMI_DUE_REMINDER,
    /** An EMI's due date passed with no payment recorded: once, then weekly. */
    EMI_OVERDUE,
    /** Money lent or borrowed reached its expected return date: on the day, once when passed, then weekly. */
    LENDING_RETURN,
    /** A reward milestone is about to close within reach, or was just achieved. */
    REWARD_MILESTONE,
    /** A reward rule's or bucket's period cap is exhausted for the current window. */
    REWARD_CAP,
    /** A job the user started (import, reconcile, rule apply) finished or failed. */
    JOB_FINISHED;

    public boolean defaultEnabled() {
        return true;
    }
}
