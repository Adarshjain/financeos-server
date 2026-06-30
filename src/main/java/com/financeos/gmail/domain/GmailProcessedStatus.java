package com.financeos.gmail.domain;

public enum GmailProcessedStatus {
    CREATED,
    SKIPPED_NOT_TRANSACTION,
    SKIPPED_BEFORE_WATERMARK,
    FAILED,
    RECONCILED
}
