package com.financeos.domain.investment;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;

import java.time.LocalDate;

/** Indian financial years (1 April to 31 March), identified by their start year. */
public final class FinancialYear {

    private FinancialYear() {
    }

    /** A closed date range. */
    public record Range(LocalDate from, LocalDate to) {
    }

    /** The start year of the financial year containing {@code date}. */
    public static int startYear(LocalDate date) {
        return date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    }

    /** The current financial year's start year by the business date. */
    public static int current() {
        return startYear(AppTime.today());
    }

    public static LocalDate start(int fy) {
        return LocalDate.of(fy, 4, 1);
    }

    public static LocalDate end(int fy) {
        return LocalDate.of(fy + 1, 3, 31);
    }

    public static Range range(int fy) {
        return new Range(start(fy), end(fy));
    }

    /** "2026-27" for the financial year starting in 2026. */
    public static String label(int fy) {
        return String.format("%d-%02d", fy, (fy + 1) % 100);
    }

    /**
     * The date window of a request: {@code fy} (a start year) as an alternative to explicit
     * {@code from}/{@code to}. Giving both is an error.
     */
    public static Range resolve(Integer fy, LocalDate from, LocalDate to) {
        if (fy == null) {
            return new Range(from, to);
        }
        if (from != null || to != null) {
            throw new ValidationException("Use either fy or from/to, not both");
        }
        if (fy < 1900 || fy > 2200) {
            throw new ValidationException("fy must be a financial year's start year, e.g. " + current());
        }
        return range(fy);
    }
}
