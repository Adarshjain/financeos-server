package com.financeos.domain.investment.dividend;

/** How strongly a bank credit's amount agrees with a dividend row. */
public enum DividendMatchTier {
    /** Within tolerance of the gross amount, or of gross minus the recorded TDS. */
    EXACT,
    /** Within tolerance of gross × 0.9 while no TDS is recorded (Section 194 deduction). */
    NET_OF_TDS,
    /** Amount only loosely agrees; narration had to name the company and carry a dividend keyword. */
    FUZZY
}
