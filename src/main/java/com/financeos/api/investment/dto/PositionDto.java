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
        BigDecimal intradayRealized,
        BigDecimal dividends,
        Double xirr,
        BigDecimal absoluteReturnPercent,
        BigDecimal totalCharges,
        String notes,
        String mergedIntoName,
        LocalDate mergedIntoDate,
        BigDecimal buyQty,
        BigDecimal buyValue,
        BigDecimal avgBuy,
        BigDecimal sellQty,
        BigDecimal sellValue,
        BigDecimal avgSell,
        BigDecimal netQty,
        Boolean unclosed
) {
    public PositionDto(
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
            BigDecimal intradayRealized,
            BigDecimal dividends,
            Double xirr,
            BigDecimal absoluteReturnPercent,
            BigDecimal totalCharges,
            String notes
    ) {
        this(holdingId, brokerAccountId, brokerName, provider, instrument, quantity, avgCost, invested, lastPrice, lastPriceAsOf, lastPriceSource, currentValue, unrealizedGainLoss, unrealizedGainLossPercent, realizedGainLoss, intradayRealized, dividends, xirr, absoluteReturnPercent, totalCharges, notes, null, null, null, null, null, null, null, null, null, null);
    }

    public PositionDto(
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
            BigDecimal intradayRealized,
            BigDecimal dividends,
            Double xirr,
            BigDecimal absoluteReturnPercent,
            BigDecimal totalCharges,
            String notes,
            String mergedIntoName,
            LocalDate mergedIntoDate
    ) {
        this(holdingId, brokerAccountId, brokerName, provider, instrument, quantity, avgCost, invested, lastPrice, lastPriceAsOf, lastPriceSource, currentValue, unrealizedGainLoss, unrealizedGainLossPercent, realizedGainLoss, intradayRealized, dividends, xirr, absoluteReturnPercent, totalCharges, notes, mergedIntoName, mergedIntoDate, null, null, null, null, null, null, null, null);
    }

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
