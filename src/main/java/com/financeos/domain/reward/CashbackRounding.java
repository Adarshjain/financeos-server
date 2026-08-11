package com.financeos.domain.reward;

/** Rounding applied to PERCENT cashback per transaction. NONE keeps paise (2 decimals). */
public enum CashbackRounding {
    NONE,
    FLOOR_RUPEE,
    NEAREST_RUPEE
}
