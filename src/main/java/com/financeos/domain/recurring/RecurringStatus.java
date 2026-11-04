package com.financeos.domain.recurring;

/**
 * PROPOSED: detected, awaiting the user. TRACKED: followed. DISMISSED: "Not regular". ENDED: stopped, or
 * missed twice. LOAN: became a loan.
 */
public enum RecurringStatus {
    PROPOSED,
    TRACKED,
    DISMISSED,
    ENDED,
    LOAN
}
