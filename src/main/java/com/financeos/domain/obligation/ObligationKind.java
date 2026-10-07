package com.financeos.domain.obligation;

/**
 * Which record a transaction is referenced by via direct FK. A transaction is referenced by at most
 * one <em>family</em>: loan rows (exactly one row, unique per table), lending rows (possibly several
 * — split bills share one bank transaction) or dividend rows (possibly several — an interim and a
 * special dividend with one record date arrive as a single credit).
 */
public enum ObligationKind {
    LENDING,
    LOAN_PAYMENT,
    LOAN_EVENT,
    LOAN_CHARGE,
    DIVIDEND
}
