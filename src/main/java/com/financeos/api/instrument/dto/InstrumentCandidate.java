package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InstrumentCandidate(
        String source,
        InstrumentType type,
        String name,
        String symbol,
        String exchange,
        String isin,
        String amfiCode,
        String yahooSymbol,
        String currency,
        PricePreview pricePreview,
        UUID existingInstrumentId
) {
    public record PricePreview(
            BigDecimal value,
            LocalDate asOf
    ) {
    }
}
