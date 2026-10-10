package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.PriceSource;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InstrumentPriceResponse(
        UUID id,
        LocalDate asOf,
        BigDecimal close,
        PriceSource source,
        /**
         * Whether the requesting user may edit or delete this price: only their own MANUAL prices.
         * Feed prices and MANUAL prices entered before prices had an owner are read-only.
         */
        boolean editable
) {
    /** The price as {@code userId} (nullable) sees it. */
    public static InstrumentPriceResponse from(InstrumentPrice price, @Nullable UUID userId) {
        return new InstrumentPriceResponse(
                price.getId(),
                price.getAsOf(),
                price.getClose(),
                price.getSource(),
                price.isOwnManual(userId)
        );
    }
}
