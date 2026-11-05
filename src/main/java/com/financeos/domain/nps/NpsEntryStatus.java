package com.financeos.domain.nps;

/** How sure the units of an entry are: ESTIMATED units are replaced by a statement; PENDING entries count for nothing yet. */
public enum NpsEntryStatus {
    CONFIRMED,
    ESTIMATED,
    PENDING
}
