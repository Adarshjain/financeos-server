package com.financeos.domain.notification.bill;

public enum PaidSource {
    NONE,
    /** The user pressed "Mark as paid". */
    MANUAL,
    /** CC_PAYMENT link(s) whose card-side credit falls after the statement period. */
    LINK
}
