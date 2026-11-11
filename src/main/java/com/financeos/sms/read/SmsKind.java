package com.financeos.sms.read;

/** What a message is. TRANSACTION is a payment (or a guess at one); the rest are explicit not-a-transaction classes. */
public enum SmsKind {
    TRANSACTION,
    OTP,
    FAILED,
    FUTURE,
    REQUEST,
    MANDATE,
    SENDER_SIDE_CONFIRMATION,
    REFUND_INITIATED,
    STATEMENT_DUE,
    OFFER,
    NOT_A_TRANSACTION,
    UNKNOWN
}
