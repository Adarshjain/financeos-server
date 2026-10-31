package com.financeos.gmail.classify;

/** Finer class of a Gmail message; every subtype belongs to exactly one {@link EmailKind}. */
public enum EmailSubtype {
    DEBIT(EmailKind.ALERT),
    CREDIT(EmailKind.ALERT),
    REVERSAL(EmailKind.ALERT),

    CARD_STATEMENT(EmailKind.STATEMENT),
    ACCOUNT_STATEMENT(EmailKind.STATEMENT),
    STATEMENT(EmailKind.STATEMENT),

    OTP(EmailKind.OTHER),
    DECLINED(EmailKind.OTHER),
    BILL_DUE(EmailKind.OTHER),
    STATEMENT_NOTICE(EmailKind.OTHER),
    UPCOMING_DEBIT(EmailKind.OTHER),
    BALANCE(EmailKind.OTHER),
    SECURITY(EmailKind.OTHER),
    SERVICE(EmailKind.OTHER),
    PROMOTION(EmailKind.OTHER),
    DOCUMENT(EmailKind.OTHER),
    UNKNOWN(EmailKind.OTHER);

    private final EmailKind kind;

    EmailSubtype(EmailKind kind) {
        this.kind = kind;
    }

    public EmailKind kind() {
        return kind;
    }
}
