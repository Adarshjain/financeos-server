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

    private AppNotificationKind() {
    }
}
