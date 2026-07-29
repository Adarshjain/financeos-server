package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
        // Set when the user picked an already-persisted (LOCAL) instrument. Lets resolve() reuse
        // that exact row instead of falling through to key-based dedup (which would create a
        // duplicate for instruments that lack isin/amfiCode/yahooSymbol/symbol+exchange).
        UUID existingInstrumentId
) {
}
