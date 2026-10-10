package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.InstrumentType;
import com.financeos.domain.instrument.PriceSource;
import com.financeos.domain.instrument.TaxClass;

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
        @Nullable Boolean unclosed,
        /** Allocation bucket of the instrument (stored, else derived by the AMFI/name rules). */
        @Nullable AssetClass assetClass,
        /** Capital-gains tax class of the instrument. */
        @Nullable TaxClass taxClass,
        /** The stored close before {@code lastPrice}; null when there is none or the position is closed. */
        @Nullable BigDecimal previousClose,
        @Nullable LocalDate previousCloseAsOf,
        /** (lastPrice − previousClose) × quantity, 2 dp. */
        @Nullable BigDecimal dayChange,
        /** (lastPrice − previousClose) ÷ previousClose × 100, 2 dp. */
        @Nullable BigDecimal dayChangePct
) {
    /** The original shape: no classification and no day change. */
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
        this(holdingId, brokerAccountId, brokerName, provider, instrument, quantity, avgCost, invested, lastPrice, lastPriceAsOf, lastPriceSource, currentValue, unrealizedGainLoss, unrealizedGainLossPercent, realizedGainLoss, intradayRealized, dividends, xirr, absoluteReturnPercent, totalCharges, notes, mergedIntoName, mergedIntoDate, buyQty, buyValue, avgBuy, sellQty, sellValue, avgSell, netQty, unclosed, null, null, null, null, null, null);
    }

    /** This position with its move since the previous stored close. */
    public PositionDto withDayChange(com.financeos.domain.investment.DayChange change) {
        return new PositionDto(holdingId, brokerAccountId, brokerName, provider, instrument, quantity, avgCost, invested, lastPrice, lastPriceAsOf, lastPriceSource, currentValue, unrealizedGainLoss, unrealizedGainLossPercent, realizedGainLoss, intradayRealized, dividends, xirr, absoluteReturnPercent, totalCharges, notes, mergedIntoName, mergedIntoDate, buyQty, buyValue, avgBuy, sellQty, sellValue, avgSell, netQty, unclosed, assetClass, taxClass,
                change.previousClose(), change.previousCloseAsOf(), change.dayChange(), change.dayChangePct());
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
            String notes
    ) {
        this(holdingId, brokerAccountId, brokerName, provider, instrument, quantity, avgCost, invested, lastPrice, lastPriceAsOf, lastPriceSource, currentValue, unrealizedGainLoss, unrealizedGainLossPercent, realizedGainLoss, intradayRealized, dividends, xirr, absoluteReturnPercent, totalCharges, notes, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
        this(holdingId, brokerAccountId, brokerName, provider, instrument, quantity, avgCost, invested, lastPrice, lastPriceAsOf, lastPriceSource, currentValue, unrealizedGainLoss, unrealizedGainLossPercent, realizedGainLoss, intradayRealized, dividends, xirr, absoluteReturnPercent, totalCharges, notes, mergedIntoName, mergedIntoDate, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
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
