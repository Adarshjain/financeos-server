package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.OptionType;

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
        UUID existingInstrumentId,
        String underlyingSymbol,
        UUID underlyingInstrumentId,
        LocalDate expiryDate,
        OptionType optionType,
        BigDecimal strikePrice,
        Integer lotSize,
        String tradingSymbol
) {
    public InstrumentCandidate(
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
        this(source, type, name, symbol, exchange, isin, amfiCode, yahooSymbol, currency, pricePreview, existingInstrumentId, null, null, null, null, null, null, null);
    }

    public record PricePreview(
            BigDecimal value,
            LocalDate asOf
    ) {
    }
}
