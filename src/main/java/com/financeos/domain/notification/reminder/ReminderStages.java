package com.financeos.domain.notification.reminder;

import com.financeos.domain.notification.MessageFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.lang.Nullable;

/**
 * The stage and texts of an in-place feed reminder, from the days left until the due date. Pure.
 * Stages: {@code D3, D2, D1, D0} (due in 3 days, tomorrow, today) then {@code OVERDUE}; a bill or
 * installment further out than {@value #WINDOW_DAYS} days has no stage yet.
 */
public final class ReminderStages {

    /** A reminder row appears this many days before the due date. */
    public static final int WINDOW_DAYS = 3;
    /** Overdue reminders stop after this many days (the existing stale limit of the EMI push). */
    public static final int OVERDUE_LIMIT_DAYS = 45;
    public static final String OVERDUE = "OVERDUE";

    private ReminderStages() {
    }

    /** The stage code for {@code daysUntilDue}, or null when it is too far out or overdue beyond the limit. */
    @Nullable
    public static String stage(long daysUntilDue) {
        if (daysUntilDue > WINDOW_DAYS) {
            return null;
        }
        if (daysUntilDue < 0) {
            return -daysUntilDue <= OVERDUE_LIMIT_DAYS ? OVERDUE : null;
        }
        return "D" + daysUntilDue;
    }

    public static boolean isOverdue(@Nullable String stage) {
        return OVERDUE.equals(stage);
    }

    /** "due in 3 days" / "due tomorrow" / "due today" / "overdue by 2 days". */
    public static String phrase(long daysUntilDue) {
        if (daysUntilDue < 0) {
            return "overdue by " + MessageFormat.days(-daysUntilDue);
        }
        return MessageFormat.inDays("due", daysUntilDue);
    }

    /** "HDFC Regalia bill due tomorrow". */
    public static String billTitle(@Nullable String cardName, long daysUntilDue) {
        return (cardName == null || cardName.isBlank() ? "Card" : cardName) + " bill " + phrase(daysUntilDue);
    }

    /** "₹12,340 due 6 Nov". */
    public static String billBody(@Nullable BigDecimal amount, @Nullable LocalDate dueDate) {
        return MessageFormat.money(amount) + " due " + MessageFormat.date(dueDate);
    }

    /** "Home loan EMI due today". */
    public static String emiTitle(@Nullable String loanName, long daysUntilDue) {
        return (loanName == null || loanName.isBlank() ? "Loan" : loanName) + " EMI " + phrase(daysUntilDue);
    }

    /** "₹9,000 due 6 Nov · #3 of 24". */
    public static String emiBody(@Nullable BigDecimal amount, @Nullable LocalDate dueDate, int seq, int total) {
        return billBody(amount, dueDate) + " · #" + seq + " of " + total;
    }
}
