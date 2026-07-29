package com.financeos.domain.instrument.price;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AmfiScheme(
        String schemeCode,
        String isin,
        String name,
        BigDecimal nav,
        LocalDate navDate
) {
}
