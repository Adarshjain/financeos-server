package com.financeos.domain.nps;

/** What a ledger entry is; only CONTRIBUTION, OPENING and WITHDRAWAL move the invested amount. */
public enum NpsEntryType {
    CONTRIBUTION,
    CHARGE,
    REBALANCE,
    SWITCH,
    MIGRATION,
    WITHDRAWAL,
    OPENING,
    ADJUSTMENT,
    OTHER
}
