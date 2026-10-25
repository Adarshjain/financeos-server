package com.financeos.api.instrument.dto;

import com.financeos.core.time.AppTime;
import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.PriceSource;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record InstrumentPriceResponse(
        UUID id,
        LocalDate asOf,
        /** When the quote was made (Yahoo last-trade time, IST offset); null for AMFI, manual and older prices. */
        @Nullable OffsetDateTime priceAt,
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
                AppTime.at(price.getPriceTime()),
                price.getClose(),
                price.getSource(),
                price.isOwnManual(userId)
        );
    }
}
