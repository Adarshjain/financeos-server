package com.financeos.domain.epf;

/** What an EPF ledger row is. The declaration order is the order rows of one date apply in the ledger. */
public enum EpfEntryType {
    OPENING_BALANCE,
    CONTRIBUTION,
    TRANSFER_IN,
    INTEREST,
    TRANSFER_OUT,
    WITHDRAWAL
}
