package com.financeos.domain.notification.bill;

/** Who paired a payment with a bill: the matcher (AUTO) or the user's "Mark paid" (USER). */
public enum BillPaymentOrigin {
    AUTO,
    USER
}
