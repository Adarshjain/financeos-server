package com.financeos.domain.instrument.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A fetched close. {@code asOf} is the IST calendar date; {@code at} the instant of the quote when the
 * feed has one (Yahoo's last-trade time), null for end-of-day feeds (AMFI NAV). {@code previousClose}
 * and {@code marketState} (e.g. REGULAR, CLOSED) are Yahoo-only and null when the feed lacks them.
 */
public record PriceQuote(
        BigDecimal close,
        LocalDate asOf,
        Instant at,
        BigDecimal previousClose,
        String marketState
) {
    /** A quote with a time but no previous close or market state. */
    public PriceQuote(BigDecimal close, LocalDate asOf, Instant at) {
        this(close, asOf, at, null, null);
    }

    /** A quote with no time (AMFI, manual). */
    public PriceQuote(BigDecimal close, LocalDate asOf) {
        this(close, asOf, null, null, null);
    }
}
