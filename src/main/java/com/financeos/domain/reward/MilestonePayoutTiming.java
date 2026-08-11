package com.financeos.domain.reward;

/**
 * When an achieved milestone's payout lands in the report: on the counted window's
 * END date (period-close crediting, the historical default) or on the day the
 * threshold was crossed (banks that credit welcome benefits immediately).
 * Either way the payout is attributed to exactly one date, so month-by-month
 * reports never count it twice.
 */
public enum MilestonePayoutTiming {
    WINDOW_END,
    ON_ACHIEVEMENT
}
