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

    /** Candidate date must fall within expected date +/- this many days. */
    public static final int MATCH_DATE_WINDOW_DAYS = 7;
}
