package com.financeos.domain.epf;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/** The EPFO's declared annual interest rate (percent) per financial year, keyed by the year's start year. */
public final class EpfInterestRates {

    /** A declared rate and the financial year it is for. */
    public record Declared(int fy, BigDecimal rate) {
    }

    private static final TreeMap<Integer, BigDecimal> RATES = new TreeMap<>(Map.ofEntries(
            Map.entry(2012, new BigDecimal("8.50")),
            Map.entry(2013, new BigDecimal("8.75")),
            Map.entry(2014, new BigDecimal("8.75")),
            Map.entry(2015, new BigDecimal("8.80")),
            Map.entry(2016, new BigDecimal("8.65")),
            Map.entry(2017, new BigDecimal("8.55")),
            Map.entry(2018, new BigDecimal("8.65")),
            Map.entry(2019, new BigDecimal("8.50")),
            Map.entry(2020, new BigDecimal("8.50")),
            Map.entry(2021, new BigDecimal("8.10")),
            Map.entry(2022, new BigDecimal("8.15")),
            Map.entry(2023, new BigDecimal("8.25")),
            Map.entry(2024, new BigDecimal("8.25")),
            Map.entry(2025, new BigDecimal("8.25"))));

    private EpfInterestRates() {
    }

    /** The declared rate for the financial year starting in {@code fy}, or null when not declared (yet). */
    public static BigDecimal rateFor(int fy) {
        return RATES.get(fy);
    }

    /** The most recent declared financial year and its rate. */
    public static Declared latestDeclared() {
        Map.Entry<Integer, BigDecimal> last = RATES.lastEntry();
        return new Declared(last.getKey(), last.getValue());
    }
}
