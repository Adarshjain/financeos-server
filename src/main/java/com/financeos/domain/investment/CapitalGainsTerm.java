package com.financeos.domain.investment;

import com.financeos.domain.instrument.TaxClass;

import java.time.LocalDate;

/**
 * The capital-gains term of a realised lot under Indian tax rules, by the instrument's
 * {@link TaxClass}:
 * <ul>
 *   <li>EQUITY_ORIENTED: {@code long} when held more than 12 months — sold after the date 12
 *       calendar months on from the buy ({@code sellDate > buyDate + 12 months}), else {@code short}.
 *       Calendar months, not 365 days: bought 2023-03-01 and sold 2024-03-01 (366 days, a leap year)
 *       is still short.</li>
 *   <li>SPECIFIED_DEBT bought on/after 2023-04-01: always {@code slab} (taxed at the slab rate, never LTCG).</li>
 *   <li>OTHER, and specified debt bought before 2023-04-01: {@code long} when held more than 24
 *       months ({@code sellDate > buyDate + 24 months}), else {@code short}.</li>
 * </ul>
 * Equity-oriented lots bought before 2018-02-01 are {@code grandfathered} (their cost for tax may be
 * the 31-Jan-2018 FMV, which is not modelled here).
 */
public final class CapitalGainsTerm {

    public static final String SHORT = "short";
    public static final String LONG = "long";
    public static final String SLAB = "slab";

    /** Specified mutual funds bought on or after this date are taxed at slab whatever the holding period. */
    public static final LocalDate SPECIFIED_DEBT_SLAB_FROM = LocalDate.of(2023, 4, 1);
    /** Equity bought before this date is grandfathered (cost may be stepped up to the 31-Jan-2018 FMV). */
    public static final LocalDate GRANDFATHERING_BEFORE = LocalDate.of(2018, 2, 1);

    private CapitalGainsTerm() {
    }

    public static String term(TaxClass taxClass, LocalDate buyDate, LocalDate sellDate) {
        TaxClass tc = taxClass == null ? TaxClass.OTHER : taxClass;
        if (tc == TaxClass.EQUITY_ORIENTED) {
            return sellDate.isAfter(buyDate.plusMonths(12)) ? LONG : SHORT;
        }
        if (tc == TaxClass.SPECIFIED_DEBT && !buyDate.isBefore(SPECIFIED_DEBT_SLAB_FROM)) {
            return SLAB;
        }
        return sellDate.isAfter(buyDate.plusMonths(24)) ? LONG : SHORT;
    }

    /**
     * The first sell date on which a lot bought on {@code buyDate} is long term: the day after
     * {@code buyDate + 12 months} for equity-oriented, the day after {@code buyDate + 24 months} for
     * the rest; null for a slab lot, which never turns long term.
     */
    public static LocalDate longTermOn(TaxClass taxClass, LocalDate buyDate) {
        TaxClass tc = taxClass == null ? TaxClass.OTHER : taxClass;
        if (tc == TaxClass.EQUITY_ORIENTED) {
            return buyDate.plusMonths(12).plusDays(1);
        }
        if (tc == TaxClass.SPECIFIED_DEBT && !buyDate.isBefore(SPECIFIED_DEBT_SLAB_FROM)) {
            return null;
        }
        return buyDate.plusMonths(24).plusDays(1);
    }

    public static boolean grandfathered(TaxClass taxClass, LocalDate buyDate) {
        return taxClass == TaxClass.EQUITY_ORIENTED && buyDate.isBefore(GRANDFATHERING_BEFORE);
    }
}
