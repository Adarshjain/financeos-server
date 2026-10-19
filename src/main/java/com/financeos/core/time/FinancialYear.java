package com.financeos.core.time;

import java.time.LocalDate;

/** An Indian financial year: 1 April to 31 March, both ends inclusive. */
public record FinancialYear(LocalDate start, LocalDate end) {

    /** The financial year containing {@code date}. */
    public static FinancialYear containing(LocalDate date) {
        int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return new FinancialYear(LocalDate.of(startYear, 4, 1), LocalDate.of(startYear + 1, 3, 31));
    }
}
