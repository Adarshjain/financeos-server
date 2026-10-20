package com.financeos.domain.notification.bill;

import com.financeos.core.exception.ValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** The shared plausibility rules for a card statement's period, due date and amounts (PATCH details and the AI fallback). */
public final class StatementDetailsRules {

    public static final int MAX_PERIOD_DAYS = 400;
    public static final int MAX_DUE_AFTER_PERIOD_END_DAYS = 60;

    private StatementDetailsRules() {
    }

    /** Sign check on the supplied (new) amounts only. */
    public static void validateAmounts(BigDecimal totalAmountDue, BigDecimal minimumAmountDue) {
        if (totalAmountDue != null && totalAmountDue.signum() < 0) {
            throw new ValidationException("Total due cannot be negative");
        }
        if (minimumAmountDue != null && minimumAmountDue.signum() < 0) {
            throw new ValidationException("Minimum due cannot be negative");
        }
    }

    /**
     * Cross-field checks. The p* arguments are the new values (null = not supplied), the others the effective
     * values after merging with what is stored. Only relations touched by a new value are checked.
     */
    public static void validateRelations(LocalDate pStart, LocalDate pEnd, LocalDate pDue, BigDecimal pTotal,
                                         BigDecimal pMin, LocalDate start, LocalDate end, LocalDate due,
                                         BigDecimal total, BigDecimal min) {
        if ((pStart != null || pEnd != null) && start != null && end != null) {
            if (start.isAfter(end)) {
                throw new ValidationException("Period start must be on or before period end");
            }
            if (ChronoUnit.DAYS.between(start, end) > MAX_PERIOD_DAYS) {
                throw new ValidationException("Statement period cannot be longer than " + MAX_PERIOD_DAYS + " days");
            }
        }
        if ((pDue != null || pEnd != null) && due != null && end != null) {
            long gap = ChronoUnit.DAYS.between(end, due);
            if (gap < 0 || gap > MAX_DUE_AFTER_PERIOD_END_DAYS) {
                throw new ValidationException("Due date must be 0 to " + MAX_DUE_AFTER_PERIOD_END_DAYS
                        + " days after the period end");
            }
        }
        if ((pTotal != null || pMin != null) && total != null && min != null && min.compareTo(total) > 0) {
            throw new ValidationException("Minimum due cannot exceed total due");
        }
    }
}
