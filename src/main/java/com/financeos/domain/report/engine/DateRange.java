package com.financeos.domain.report.engine;

import java.time.LocalDate;

/**
 * An inclusive date window {@code [from, to]}. When {@code bounded} is false the window is
 * open (used for {@code all_time}), and {@code from}/{@code to} are null.
 */
public record DateRange(LocalDate from, LocalDate to, boolean bounded) {

    public static DateRange unbounded() {
        return new DateRange(null, null, false);
    }

    public static DateRange of(LocalDate from, LocalDate to) {
        return new DateRange(from, to, true);
    }

    /** Inclusive length in days (0 when unbounded). */
    public long lengthDays() {
        return bounded ? (to.toEpochDay() - from.toEpochDay() + 1) : 0;
    }

    /** Whether the date falls within this range inclusive (always true when unbounded). */
    public boolean contains(LocalDate date) {
        if (!bounded || date == null) {
            return true;
        }
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }
}
