package com.financeos.domain.instrument.price;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceQuote(
        BigDecimal close,
        LocalDate asOf
) {
}
