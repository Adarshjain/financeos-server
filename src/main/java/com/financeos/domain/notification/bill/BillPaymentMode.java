package com.financeos.domain.notification.bill;

/** How "Mark paid" also records the payment: not at all (today's manual mark), from an existing bank debit, or as new transactions. */
public enum BillPaymentMode {
    NONE,
    EXISTING,
    NEW
}
