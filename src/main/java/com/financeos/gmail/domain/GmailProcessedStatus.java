package com.financeos.gmail.domain;

public enum GmailProcessedStatus {
    DISCOVERED,
    PROCESSING,
    CREATED,
    RECONCILED,
    SKIPPED_NOT_TRANSACTION,
    SKIPPED_BEFORE_WATERMARK,
    SKIPPED_SENDER_DISABLED,
    SKIPPED_DUPLICATE,
    SKIPPED_MESSAGE_GONE,
    UNRESOLVED_ACCOUNT,
    ACCOUNT_NOT_OPTED_IN,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    CLEANED_UP,
    /** A document handler (NPS) imported the statement. */
    NPS_IMPORTED,
    NPS_PASSWORD_NEEDED,
    NPS_ACCOUNT_NEEDED,
    NPS_ENTRY,
    NPS_IGNORED,
    /** An admin gave up on a failed message (Failures tool): terminal, never drained, retried or shown as attention. */
    SKIPPED_BY_ADMIN
}
