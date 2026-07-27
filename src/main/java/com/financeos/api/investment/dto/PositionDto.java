package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PositionDto(
        UUID holdingId,
        UUID brokerAccountId,
        String brokerName,
        String provider,
        InstrumentInfoDto instrument,
        BigDecimal quantity,
        BigDecimal avgCost,
        BigDecimal invested,
        BigDecimal lastPrice,
        LocalDate lastPriceAsOf,
        PriceSource lastPriceSource,
        BigDecimal currentValue,
        BigDecimal unrealizedGainLoss,
        BigDecimal unrealizedGainLossPercent,
        BigDecimal realizedGainLoss,
        BigDecimal dividends,
        Double xirr,
        BigDecimal absoluteReturnPercent,
        BigDecimal totalCharges,
        String notes
) {
    public record InstrumentInfoDto(
            UUID id,
            InstrumentType type,
            String name,
            String symbol,
            String isin,
            String amfiCode,
            String yahooSymbol,
            PriceSource lastPriceSource
    ) {}
}
