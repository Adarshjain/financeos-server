package com.financeos.domain.instrument.price;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One scheme line of the AMFI NAV feed. {@code category} is the scheme-category header the line
 * sits under, e.g. "Open Ended Schemes(Equity Scheme - Large Cap Fund)"; null when none preceded it.
 */
public record AmfiScheme(
        String schemeCode,
        String isin,
        String name,
        BigDecimal nav,
        LocalDate navDate,
        @Nullable String category
) {
    public AmfiScheme(String schemeCode, String isin, String name, BigDecimal nav, LocalDate navDate) {
        this(schemeCode, isin, name, nav, navDate, null);
    }
}
