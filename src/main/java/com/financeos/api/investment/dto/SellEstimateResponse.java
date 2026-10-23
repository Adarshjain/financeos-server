package com.financeos.api.investment.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What selling {@code quantity} of an instrument today would book: the oldest lots first (across broker
 * accounts), the gain split by term and, for equity-oriented holdings only, the tax it adds this
 * financial year. The rates, tax and exemption figures are null for other asset classes (their
 * short-term gain includes slab lots). {@code noPrice}: no price known, so proceeds are taken at cost.
 */
public record SellEstimateResponse(
        UUID instrumentId,
        BigDecimal quantity,
        @Nullable BigDecimal price,
        boolean noPrice,
        BigDecimal proceeds,
        BigDecimal cost,
        BigDecimal gain,
        BigDecimal longTermGain,
        BigDecimal shortTermGain,
        boolean equity,
        int fy,
        @Nullable BigDecimal stcgRate,
        @Nullable BigDecimal ltcgRate,
        @Nullable BigDecimal stcgTax,
        @Nullable BigDecimal ltcgTax,
        @Nullable BigDecimal tax,
        @Nullable BigDecimal exemptionLeftBefore,
        @Nullable BigDecimal exemptionLeftAfter,
        List<Lot> lots
) {
    public record Lot(LocalDate buyDate, BigDecimal quantity, BigDecimal costPerUnit, BigDecimal cost,
                      BigDecimal proceeds, BigDecimal gain, String term) {}
}
