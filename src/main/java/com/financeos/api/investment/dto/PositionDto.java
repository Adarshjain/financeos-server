package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;

import org.springframework.lang.Nullable;

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
        @Nullable BigDecimal lastPrice,
        @Nullable LocalDate lastPriceAsOf,
        @Nullable PriceSource lastPriceSource,
        @Nullable BigDecimal currentValue,
        @Nullable BigDecimal unrealizedGainLoss,
        @Nullable BigDecimal unrealizedGainLossPercent,
        @Nullable BigDecimal realizedGainLoss,
        @Nullable BigDecimal intradayRealized,
        @Nullable BigDecimal dividends,
        @Nullable Double xirr,
        @Nullable BigDecimal absoluteReturnPercent,
        @Nullable BigDecimal totalCharges,
        @Nullable String notes,
        @Nullable String mergedIntoName,
        @Nullable LocalDate mergedIntoDate,
        @Nullable BigDecimal buyQty,
        @Nullable BigDecimal buyValue,
        @Nullable BigDecimal avgBuy,
        @Nullable BigDecimal sellQty,
        @Nullable BigDecimal sellValue,
        @Nullable BigDecimal avgSell,
        @Nullable BigDecimal netQty,
        @Nullable Boolean unclosed
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
            @Nullable String symbol,
            @Nullable String isin,
            @Nullable String amfiCode,
            @Nullable String yahooSymbol,
            @Nullable PriceSource lastPriceSource
    ) {}
}
