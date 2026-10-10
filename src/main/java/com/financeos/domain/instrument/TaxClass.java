package com.financeos.domain.instrument;

/**
 * How Indian capital-gains tax treats an instrument (derived, never stored).
 * <ul>
 *   <li>{@code EQUITY_ORIENTED}: listed shares, equity funds/ETFs and equity-oriented hybrids
 *       (aggressive, arbitrage, equity savings, balanced advantage / dynamic asset allocation).
 *       Long term after 12 months.</li>
 *   <li>{@code SPECIFIED_DEBT}: debt funds and debt/liquid ETFs. Bought on/after 2023-04-01 the gain
 *       is always taxed at slab; bought earlier it is treated like {@code OTHER}.</li>
 *   <li>{@code OTHER}: gold, international, conservative/balanced/multi-asset hybrids and the rest.
 *       Long term after 24 months.</li>
 * </ul>
 */
public enum TaxClass {
    EQUITY_ORIENTED,
    SPECIFIED_DEBT,
    OTHER
}
