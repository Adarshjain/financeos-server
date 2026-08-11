package com.financeos.domain.reward;

/**
 * The clock a period cap measures against. STATEMENT_CYCLE resolves from the
 * statements engine; when no statement covers a date it falls back to
 * CALENDAR_MONTH (flagged on the report).
 */
public enum CapWindow {
    DAY,
    CALENDAR_MONTH,
    STATEMENT_CYCLE,
    QUARTER,
    CALENDAR_YEAR,
    /** 12-month window anchored on the account's reward anniversary date. */
    ANNIVERSARY_YEAR
}
