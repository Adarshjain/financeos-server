package com.financeos.domain.transaction;

public enum TransactionSource {
    gmail_transaction_alert,
    gmail_statement,
    manual,
    file_upload,
    sms_alert;

    /** A row made from a bank's own alert (email or text message): a statement line wins over it and merges with it. */
    public boolean isAlert() {
        return this == gmail_transaction_alert || this == sms_alert;
    }
}
