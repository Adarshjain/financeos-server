package com.financeos.domain.obligation;

import java.math.BigDecimal;

/**
 * Shared tuning knobs for bank-transaction match suggestions across loans and lendings
 * (installment/EMI matching in {@code LoanService}, ledger-entry matching in {@code LendingService}).
 */
public final class MatchingConstants {

    private MatchingConstants() {
        // Utility class
    }

    /** Candidate amount must fall within expected amount +/- this tolerance. */
    public static final BigDecimal MATCH_AMOUNT_TOLERANCE = new BigDecimal("20");

    /** Loan EMI tolerance floor (rupees). */
    public static final BigDecimal LOAN_MATCH_AMOUNT_TOLERANCE_MIN = new BigDecimal("100");

    /** Loan EMI tolerance as a fraction of the expected EMI (1%). */
    public static final BigDecimal LOAN_MATCH_AMOUNT_TOLERANCE_RATE = new BigDecimal("0.01");

    /**
     * Loan EMI matching tolerance: max(100, 1% of the expected EMI). Lendings keep the flat
     * {@link #MATCH_AMOUNT_TOLERANCE}.
     */
    public static BigDecimal loanAmountTolerance(BigDecimal expected) {
        BigDecimal pct = expected.abs().multiply(LOAN_MATCH_AMOUNT_TOLERANCE_RATE);
        return pct.max(LOAN_MATCH_AMOUNT_TOLERANCE_MIN).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Candidate date must fall within expected date +/- this many days. */
    public static final int MATCH_DATE_WINDOW_DAYS = 7;
}
