package com.financeos.domain.notification.bill;

/** Computed on read from the statement, the user's manual mark and linked payments. */
public enum BillStatus {
    /** Zero or credit balance: nothing to pay. */
    NO_DUE,
    /** The parser found no due date or no total; the user has to fill it in. */
    DUE_UNKNOWN,
    OPEN,
    /** Something paid, due date not yet passed. */
    PARTIAL,
    PAID,
    /** Due date passed and not paid in full (whatever was paid is still shown). */
    OVERDUE
}
