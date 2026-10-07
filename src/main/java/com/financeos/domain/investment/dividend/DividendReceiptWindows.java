package com.financeos.domain.investment.dividend;

import java.time.LocalDate;

/**
 * Expected payout window per dividend source and the status derivation built on it.
 *
 * <ul>
 *   <li>{@code import} (CAS) rows carry the real payout date → pay date ±{@value #IMPORT_WINDOW_DAYS}d.</li>
 *   <li>{@code suggested} (Yahoo) rows default pay date to the ex-date, so the window keys off the
 *       ex-date: −{@value #SUGGESTED_BEFORE_DAYS}d … +{@value #SUGGESTED_AFTER_DAYS}d (final dividends
 *       pay within 30 days of the AGM, weeks after the record date).</li>
 *   <li>Everything else (manual) → pay date ±{@value #MANUAL_WINDOW_DAYS}d.</li>
 * </ul>
 *
 * <p>The list filter cannot evaluate the Java derivation, so {@link #thresholds} turns the same
 * rules into per-source cut-off dates that the JPQL compares the base date against.
 */
public final class DividendReceiptWindows {

    private DividendReceiptWindows() {
    }

    public static final String SOURCE_SUGGESTED = "suggested";
    public static final String SOURCE_IMPORT = "import";
    public static final String SOURCE_MANUAL = "manual";

    public static final int SUGGESTED_BEFORE_DAYS = 3;
    public static final int SUGGESTED_AFTER_DAYS = 60;
    public static final int IMPORT_WINDOW_DAYS = 5;
    public static final int MANUAL_WINDOW_DAYS = 10;

    /** Sentinel bound when the user has no tracked bank data at all: nothing is on or before it. */
    public static final LocalDate NO_COVERAGE = LocalDate.of(1900, 1, 1);

    public record Window(LocalDate from, LocalDate to) {
    }

    /** Per-source cut-offs the list query binds (see {@link DividendRepository#RECEIPT_PREDICATE}). */
    public record Thresholds(
            LocalDate suggestedAwaitFrom, LocalDate importAwaitFrom, LocalDate manualAwaitFrom,
            LocalDate suggestedOverdueBefore, LocalDate importOverdueBefore, LocalDate manualOverdueBefore) {
    }

    public static String sourceOf(String source) {
        return source == null || source.isBlank() ? SOURCE_MANUAL : source;
    }

    public static int beforeDays(String source) {
        return switch (sourceOf(source)) {
            case SOURCE_SUGGESTED -> SUGGESTED_BEFORE_DAYS;
            case SOURCE_IMPORT -> IMPORT_WINDOW_DAYS;
            default -> MANUAL_WINDOW_DAYS;
        };
    }

    public static int afterDays(String source) {
        return switch (sourceOf(source)) {
            case SOURCE_SUGGESTED -> SUGGESTED_AFTER_DAYS;
            case SOURCE_IMPORT -> IMPORT_WINDOW_DAYS;
            default -> MANUAL_WINDOW_DAYS;
        };
    }

    /** Suggested rows anchor on the ex-date when present; everything else on the pay date. */
    public static LocalDate baseDate(String source, LocalDate exDate, LocalDate payDate) {
        if (SOURCE_SUGGESTED.equals(sourceOf(source)) && exDate != null) {
            return exDate;
        }
        return payDate;
    }

    public static LocalDate baseDate(Dividend d) {
        return baseDate(d.getSource(), d.getExDate(), d.getPayDate());
    }

    public static Window of(String source, LocalDate exDate, LocalDate payDate) {
        LocalDate base = baseDate(source, exDate, payDate);
        return new Window(base.minusDays(beforeDays(source)), base.plusDays(afterDays(source)));
    }

    public static Window of(Dividend d) {
        return of(d.getSource(), d.getExDate(), d.getPayDate());
    }

    public static DividendReceiptStatus derive(Dividend d, LocalDate today, LocalDate coverageEnd) {
        return derive(d.getSource(), d.getExDate(), d.getPayDate(),
                d.getTransaction() != null, d.getReceiptStatus(), today, coverageEnd);
    }

    public static DividendReceiptStatus derive(String source, LocalDate exDate, LocalDate payDate,
                                               boolean linked, DividendReceiptStatus manual,
                                               LocalDate today, LocalDate coverageEnd) {
        if (linked) {
            return DividendReceiptStatus.received;
        }
        if (manual != null) {
            return manual;
        }
        return deriveUnresolved(of(source, exDate, payDate).to(), today, coverageEnd);
    }

    public static DividendReceiptStatus deriveUnresolved(LocalDate windowEnd, LocalDate today, LocalDate coverageEnd) {
        if (!windowEnd.isBefore(today)) {
            return DividendReceiptStatus.awaiting;
        }
        if (coverageEnd != null && !windowEnd.isAfter(coverageEnd)) {
            return DividendReceiptStatus.overdue;
        }
        return DividendReceiptStatus.unverifiable;
    }

    /**
     * awaiting ⇔ base ≥ today − after; overdue ⇔ base &lt; min(today, coverageEnd + 1) − after;
     * unverifiable ⇔ neither. Mirrors {@link #deriveUnresolved} exactly.
     */
    public static Thresholds thresholds(LocalDate today, LocalDate coverageEnd) {
        LocalDate cutoff;
        if (coverageEnd == null) {
            cutoff = NO_COVERAGE;
        } else {
            LocalDate dayAfterCoverage = coverageEnd.plusDays(1);
            cutoff = dayAfterCoverage.isBefore(today) ? dayAfterCoverage : today;
        }
        return new Thresholds(
                today.minusDays(SUGGESTED_AFTER_DAYS),
                today.minusDays(IMPORT_WINDOW_DAYS),
                today.minusDays(MANUAL_WINDOW_DAYS),
                cutoff.minusDays(SUGGESTED_AFTER_DAYS),
                cutoff.minusDays(IMPORT_WINDOW_DAYS),
                cutoff.minusDays(MANUAL_WINDOW_DAYS));
    }
}
