package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InstrumentRequest(
        @NotNull(message = "Instrument type is required") InstrumentType type,
        @NotBlank(message = "Name is required") String name,
        String symbol,
        String exchange,
        String isin,
        String amfiCode,
        String yahooSymbol,
        String currency
) {
}
