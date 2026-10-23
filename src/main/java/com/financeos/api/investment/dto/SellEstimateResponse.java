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
 * short-term gain includes slab lots). {@code bookedShortTermGain} / {@code bookedEquityLongTermGain} / {@code bookedOtherLongTermGain}: the
 * financial year's already-booked net P&amp;L (signed, all instruments, before this sale; short term
 * includes slab lots; long term split equity-oriented vs other, as the set-off nets them) and {@code exemptionLimit} the year's limit, so a client can redo the set-off
 * for any quantity; all four null for other asset classes. {@code noPrice}: no price known, so proceeds are taken at cost.
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
        List<Lot> lots,
        @Nullable BigDecimal bookedShortTermGain,
        @Nullable BigDecimal bookedEquityLongTermGain,
        @Nullable BigDecimal bookedOtherLongTermGain,
        @Nullable BigDecimal exemptionLimit
) {
    public record Lot(LocalDate buyDate, BigDecimal quantity, BigDecimal costPerUnit, BigDecimal cost,
                      BigDecimal proceeds, BigDecimal gain, String term) {}
}
