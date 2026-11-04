package com.financeos.domain.recurring;

import java.time.LocalDate;

/**
 * The interval of a series. {@code minGap}..{@code maxGap} is the day gap between two consecutive payments
 * that still counts as one period; {@code windowDays} is how far a payment may sit from its due date and still
 * belong to that cycle; {@code nominalDays} sizes "no older than 1.5 periods".
 */
public enum RecurringPeriod {
    WEEKLY(5, 9, 7, 2),
    MONTHLY(27, 34, 30, 5),
    QUARTERLY(85, 97, 91, 15),
    YEARLY(355, 375, 365, 30);

    private final int minGap;
    private final int maxGap;
    private final int nominalDays;
    private final int windowDays;

    RecurringPeriod(int minGap, int maxGap, int nominalDays, int windowDays) {
        this.minGap = minGap;
        this.maxGap = maxGap;
        this.nominalDays = nominalDays;
        this.windowDays = windowDays;
    }

    public boolean fits(long gapDays) {
        return gapDays >= minGap && gapDays <= maxGap;
    }

    public int windowDays() {
        return windowDays;
    }

    public int nominalDays() {
        return nominalDays;
    }

    /** The longest the last payment may lie back: 1.5 periods. */
    public int maxStaleDays() {
        return nominalDays * 3 / 2;
    }

    /** The due date one cycle after {@code from}. */
    public LocalDate next(LocalDate from) {
        return switch (this) {
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case YEARLY -> from.plusYears(1);
        };
    }
}
