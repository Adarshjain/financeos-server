package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.OptionType;
import com.financeos.domain.instrument.PriceSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public record InstrumentResponse(
        UUID id,
        InstrumentType type,
        String name,
        String symbol,
        String exchange,
        String isin,
        String amfiCode,
        String yahooSymbol,
        String currency,
        BigDecimal lastPrice,
        LocalDate lastPriceAsOf,
        PriceSource lastPriceSource,
        Instant createdAt,
        Instant updatedAt,
        String underlyingSymbol,
        UUID underlyingInstrumentId,
        LocalDate expiryDate,
        OptionType optionType,
        BigDecimal strikePrice,
        Integer lotSize,
        String tradingSymbol
) {
    public static InstrumentResponse from(Instrument instrument, Optional<InstrumentPrice> latestPrice) {
        return new InstrumentResponse(
                instrument.getId(),
                instrument.getType(),
                instrument.getName(),
                instrument.getSymbol(),
                instrument.getExchange(),
                instrument.getIsin(),
                instrument.getAmfiCode(),
                instrument.getYahooSymbol(),
                instrument.getCurrency() != null ? instrument.getCurrency() : "INR",
                latestPrice.map(InstrumentPrice::getClose).orElse(null),
                latestPrice.map(InstrumentPrice::getAsOf).orElse(null),
                latestPrice.map(InstrumentPrice::getSource).orElse(null),
                instrument.getCreatedAt(),
                instrument.getUpdatedAt(),
                instrument.getUnderlyingSymbol(),
                instrument.getUnderlyingInstrumentId(),
                instrument.getExpiryDate(),
                instrument.getOptionType(),
                instrument.getStrikePrice(),
                instrument.getLotSize(),
                instrument.getTradingSymbol()
        );
    }
}
