package com.financeos.domain.obligation;

/**
 * Which loan/lending record a transaction is referenced by. A transaction is referenced by at most
 * one <em>family</em>: either loan rows (exactly one row, unique per table) or lending rows
 * (possibly several — split bills share one bank transaction).
 */
public enum ObligationKind {
    LENDING,
    LOAN_PAYMENT,
    LOAN_EVENT,
    LOAN_CHARGE
}
