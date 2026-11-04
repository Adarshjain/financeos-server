package com.financeos.domain.notification.feed;

/** The {@code kind} values of the in-app feed (stored as text; the client maps them to a look). */
public final class AppNotificationKind {

    public static final String JOB_FAILED = "job_failed";
    public static final String JOB_FINISHED = "job_finished";
    public static final String GMAIL_SYNC = "gmail_sync";
    public static final String STATEMENT_IMPORTED = "statement_imported";
    public static final String GMAIL_DISCONNECTED = "gmail_disconnected";
    public static final String REWARD_MILESTONE = "reward_milestone";
    public static final String REWARD_CAP = "reward_cap";
    public static final String AUTO_LINKED = "auto_linked";
    public static final String ACCOUNTS_FOUND = "accounts_found";
    /** A card bill that is due soon or overdue; one row per bill, updated in place, resolved when paid. */
    public static final String BILL_REMINDER = "bill_reminder";
    /** A loan EMI installment that is due soon or overdue; one row per installment, same life cycle. */
    public static final String EMI_REMINDER = "emi_reminder";
    public static final String UPLOAD_HELD = "upload_held";

    private AppNotificationKind() {
    }
}
