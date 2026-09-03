package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.Instrument;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public record InstrumentResponse(
        UUID id,
        InstrumentType type,
        String name,
        @Nullable String symbol,
        @Nullable String exchange,
        @Nullable String isin,
        @Nullable String amfiCode,
        @Nullable String yahooSymbol,
        String currency,
        @Nullable BigDecimal lastPrice,
        @Nullable LocalDate lastPriceAsOf,
        @Nullable PriceSource lastPriceSource,
        Instant createdAt,
        Instant updatedAt
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
                instrument.getUpdatedAt()
        );
    }
}
