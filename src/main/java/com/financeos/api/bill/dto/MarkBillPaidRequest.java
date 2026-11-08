package com.financeos.api.bill.dto;

import com.financeos.domain.notification.bill.BillPaymentMode;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * {@code amount} null = paid in full (EXISTING: the debit's amount); {@code paidOn} null = today (EXISTING: the debit's date).
 * {@code payment} null or mode NONE = only the manual mark, as before.
 */
public record MarkBillPaidRequest(@Nullable @Positive BigDecimal amount, @Nullable LocalDate paidOn,
                                  @Nullable Payment payment) {

    /** Also record the payment as transactions: EXISTING needs {@code fromTransactionId}; NEW may name {@code fromAccountId} (absent = card-side credit only). */
    public record Payment(@Nullable BillPaymentMode mode, @Nullable UUID fromTransactionId, @Nullable UUID fromAccountId) {
    }

    public MarkBillPaidRequest(@Nullable BigDecimal amount, @Nullable LocalDate paidOn) {
        this(amount, paidOn, null);
    }
}
