package com.financeos.domain.transaction;

public enum ReviewReason {
    /** Statement matching no longer adds it; old rows may still carry it. */
    UNRECONCILED,
    /** Statement matching no longer adds it; old rows may still carry it. */
    DUPLICATE_SUSPECT,
    /** An email alert's date was later than the day the email was sent and was capped to that day. */
    DATE_CORRECTED,
    /** An alert dated inside a statement's period (before its last W days) that no line of it paired with. */
    NOT_ON_STATEMENT
}
