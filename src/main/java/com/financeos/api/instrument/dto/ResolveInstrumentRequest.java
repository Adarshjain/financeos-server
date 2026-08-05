package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.OptionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ResolveInstrumentRequest(
        @NotNull(message = "Instrument type is required") InstrumentType type,
        @NotBlank(message = "Name is required") String name,
        String symbol,
        String exchange,
        String isin,
        String amfiCode,
        String yahooSymbol,
        String currency,
        UUID existingInstrumentId,
        String underlyingSymbol,
        UUID underlyingInstrumentId,
        LocalDate expiryDate,
        OptionType optionType,
        BigDecimal strikePrice,
        Integer lotSize,
        String tradingSymbol
) {
    public ResolveInstrumentRequest(
            InstrumentType type,
            String name,
            String symbol,
            String exchange,
            String isin,
            String amfiCode,
            String yahooSymbol,
            String currency,
            UUID existingInstrumentId
    ) {
        this(type, name, symbol, exchange, isin, amfiCode, yahooSymbol, currency, existingInstrumentId, null, null, null, null, null, null, null);
    }
}
