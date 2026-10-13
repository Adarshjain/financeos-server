package com.financeos.domain.notification.bill;

public enum PaidSource {
    NONE,
    /** The user pressed "Mark as paid". */
    MANUAL,
    /**
     * Credits on the card dated after the statement's last billed day — payments, refunds, cashback,
     * linked or not — the same credits the account balance subtracts.
     */
    CREDITS
}
