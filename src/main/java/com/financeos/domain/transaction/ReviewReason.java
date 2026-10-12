package com.financeos.domain.transaction;

public enum ReviewReason {
    UNRECONCILED,
    CATEGORY_UNVERIFIED,
    DUPLICATE_SUSPECT,
    /** An email alert's date was later than the day the email was sent and was capped to that day. */
    DATE_CORRECTED
}
