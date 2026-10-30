package com.financeos.domain.obligation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The pure "does this transaction fit that obligation" rules, shared by the manual match-suggestion views
 * ({@code LoanService}, {@code LendingService}) and the auto-linkers, so the two never drift.
 */
public final class MatchRules {

    private MatchRules() {
        // Utility class
    }

    /**
     * An installment of {@code emi} due {@code dueDate} fits a DEBIT of {@code amount} on {@code date} when the
     * amount is within {@link MatchingConstants#loanAmountTolerance}, the date within
     * {@link MatchingConstants#MATCH_DATE_WINDOW_DAYS} and, when the loan has a payment account, the debit is on it.
     */
    public static boolean loanInstallmentFits(BigDecimal emi, LocalDate dueDate, UUID paymentAccountId,
                                              UUID txnAccountId, BigDecimal amount, LocalDate date) {
        if (paymentAccountId != null && !paymentAccountId.equals(txnAccountId)) {
            return false;
        }
        BigDecimal expected = emi.setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal tolerance = MatchingConstants.loanAmountTolerance(expected);
        return withinAmount(expected, tolerance, amount) && withinDays(dueDate, date);
    }

    /** A lending entry fits a transaction within {@code tolerance} of its amount and the match date window of its entry date. */
    public static boolean lendingEntryFits(BigDecimal entryAmount, LocalDate entryDate, BigDecimal amount, LocalDate date,
                                           BigDecimal tolerance) {
        return withinAmount(entryAmount, tolerance, amount) && withinDays(entryDate, date);
    }

    private static boolean withinAmount(BigDecimal expected, BigDecimal tolerance, BigDecimal amount) {
        return amount.compareTo(expected.subtract(tolerance)) >= 0 && amount.compareTo(expected.add(tolerance)) <= 0;
    }

    private static boolean withinDays(LocalDate expected, LocalDate date) {
        return !date.isBefore(expected.minusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS))
                && !date.isAfter(expected.plusDays(MatchingConstants.MATCH_DATE_WINDOW_DAYS));
    }
}
