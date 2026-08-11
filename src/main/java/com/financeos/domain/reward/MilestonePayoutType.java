package com.financeos.domain.reward;

/**
 * CASH_VALUE pays a fixed rupee value (voucher/bonus) once per achieved window and
 * counts toward the report's gross value. INFO_TRACKER only tracks progress —
 * e.g. an annual-fee-waiver spend target.
 */
public enum MilestonePayoutType {
    CASH_VALUE,
    INFO_TRACKER
}
