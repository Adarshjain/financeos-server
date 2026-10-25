package com.financeos.domain.instrument.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A fetched close. {@code asOf} is the IST calendar date; {@code at} the instant of the quote when the
 * feed has one (Yahoo's last-trade time), null for end-of-day feeds (AMFI NAV).
 */
public record PriceQuote(
        BigDecimal close,
        LocalDate asOf,
        Instant at
) {
    /** A quote with no time (AMFI, manual). */
    public PriceQuote(BigDecimal close, LocalDate asOf) {
        this(close, asOf, null);
    }
}
