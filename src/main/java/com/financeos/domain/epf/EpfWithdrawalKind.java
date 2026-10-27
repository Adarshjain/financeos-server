package com.financeos.domain.epf;

public enum EpfWithdrawalKind {
    PARTIAL,
    FINAL_SETTLEMENT,
    /** The pension (EPS) fund paid out; never part of the EPF balance. */
    PENSION
}
