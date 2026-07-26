package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PositionDto(
        UUID holdingId,
        BrokerInfoDto broker,
        InstrumentInfoDto instrument,
        BigDecimal openQty,
        BigDecimal avgCost,
        BigDecimal openCost,
        BigDecimal latestPrice,
        LocalDate priceAsOf,
        BigDecimal currentValue,
        BigDecimal unrealized,
        BigDecimal unrealizedPercent,
        BigDecimal realized,
        BigDecimal totalCharges,
        String notes
) {
    public record BrokerInfoDto(
            UUID id,
            String name,
            String provider
    ) {}

    public record InstrumentInfoDto(
            UUID id,
            InstrumentType type,
            String name,
            String symbol,
            String isin
    ) {}
}
