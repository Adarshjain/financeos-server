package com.financeos.domain.transaction;

/** How the transaction was made — reward rules can match on this. */
public enum TransactionChannel {
    ONLINE,
    POS,
    UPI,
    CONTACTLESS,
    ATM,
    OTHER
}
