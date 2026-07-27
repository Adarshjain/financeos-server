package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentPrice;
import com.financeos.domain.instrument.PriceSource;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InstrumentPriceResponse(
        LocalDate asOf,
        BigDecimal close,
        PriceSource source
) {
    public static InstrumentPriceResponse from(InstrumentPrice price) {
        return new InstrumentPriceResponse(
                price.getAsOf(),
                price.getClose(),
                price.getSource()
        );
    }
}
