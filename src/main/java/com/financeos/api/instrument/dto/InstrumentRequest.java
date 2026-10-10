package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InstrumentRequest(
        @NotNull(message = "Instrument type is required") InstrumentType type,
        @NotBlank(message = "Name is required") @Size(max = 255, message = "Name is at most 255 characters") String name,
        @Size(max = 50, message = "Symbol is at most 50 characters") String symbol,
        @Size(max = 20, message = "Exchange is at most 20 characters") String exchange,
        @Size(max = 50, message = "ISIN is at most 50 characters") String isin,
        @Size(max = 50, message = "AMFI code is at most 50 characters") String amfiCode,
        @Size(max = 50, message = "Yahoo symbol is at most 50 characters") String yahooSymbol,
        @Size(max = 10, message = "Currency is at most 10 characters") String currency
) {
}
