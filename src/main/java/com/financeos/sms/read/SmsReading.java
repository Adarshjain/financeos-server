package com.financeos.sms.read;

import com.financeos.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The reader's result for one message. Fields other than kind and status are null when the text does not give them. {@code directionCertain} is false for a
 * generic reading whose wording is mixed or unsettled: it is only ever shown to the owner, never matched. */
public record SmsReading(SmsKind kind, SmsReadStatus status, String templateKey, BigDecimal amount, TransactionType direction,
                         String ending, String refNo, BigDecimal balanceAfter, String counterparty, LocalDate txnDate,
                         boolean directionCertain) {

    /** A reading whose direction is certain (every template reading, and a generic one with plain wording). */
    public SmsReading(SmsKind kind, SmsReadStatus status, String templateKey, BigDecimal amount, TransactionType direction,
                      String ending, String refNo, BigDecimal balanceAfter, String counterparty, LocalDate txnDate) {
        this(kind, status, templateKey, amount, direction, ending, refNo, balanceAfter, counterparty, txnDate, true);
    }

    public static SmsReading ignored(SmsKind kind) {
        return new SmsReading(kind, SmsReadStatus.IGNORED, null, null, null, null, null, null, null, null);
    }

    public static SmsReading couldNotRead() {
        return new SmsReading(SmsKind.UNKNOWN, SmsReadStatus.COULD_NOT_READ, null, null, null, null, null, null, null, null);
    }
}
