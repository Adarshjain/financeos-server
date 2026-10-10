package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SummaryResponse(
        BigDecimal totalInvested,
        BigDecimal totalCurrentValue,
        BigDecimal totalUnrealized,
        BigDecimal totalUnrealizedPercent,
        BigDecimal totalRealized,
        BigDecimal totalIntradayRealized,
        BigDecimal totalCharges,
        BigDecimal totalDividends,
        BigDecimal totalPnl,
        @Nullable Double xirr,
        BigDecimal absoluteReturnPercent,
        List<BrokerSummaryDto> byBroker,
        List<InstrumentTypeSummaryDto> byInstrumentType,
        BigDecimal totalFnoRealized,
        /** Sum of open positions' moves since their previous stored close; null when none has one. */
        @Nullable BigDecimal dayChange,
        /** dayChange ÷ those positions' value at the previous close × 100, 2 dp. */
        @Nullable BigDecimal dayChangePct,
        /** The newest price date among open positions. */
        @Nullable LocalDate priceAsOf,
        /** The newest previous-close date among positions with a day change. */
        @Nullable LocalDate previousPriceAsOf
) {
    public SummaryResponse(
            BigDecimal totalInvested,
            BigDecimal totalCurrentValue,
            BigDecimal totalUnrealized,
            BigDecimal totalUnrealizedPercent,
            BigDecimal totalRealized,
            BigDecimal totalIntradayRealized,
            BigDecimal totalCharges,
            BigDecimal totalDividends,
            BigDecimal totalPnl,
            @Nullable Double xirr,
            BigDecimal absoluteReturnPercent,
            List<BrokerSummaryDto> byBroker,
            List<InstrumentTypeSummaryDto> byInstrumentType,
            BigDecimal totalFnoRealized
    ) {
        this(totalInvested, totalCurrentValue, totalUnrealized, totalUnrealizedPercent, totalRealized, totalIntradayRealized, totalCharges, totalDividends, totalPnl, xirr, absoluteReturnPercent, byBroker, byInstrumentType, totalFnoRealized, null, null, null, null);
    }

    public SummaryResponse(
            BigDecimal totalInvested,
            BigDecimal totalCurrentValue,
            BigDecimal totalUnrealized,
            BigDecimal totalUnrealizedPercent,
            BigDecimal totalRealized,
            BigDecimal totalIntradayRealized,
            BigDecimal totalCharges,
            BigDecimal totalDividends,
            BigDecimal totalPnl,
            @Nullable Double xirr,
            BigDecimal absoluteReturnPercent,
            List<BrokerSummaryDto> byBroker,
            List<InstrumentTypeSummaryDto> byInstrumentType
    ) {
        this(totalInvested, totalCurrentValue, totalUnrealized, totalUnrealizedPercent, totalRealized, totalIntradayRealized, totalCharges, totalDividends, totalPnl, xirr, absoluteReturnPercent, byBroker, byInstrumentType, BigDecimal.ZERO);
    }

    public record BrokerSummaryDto(
            UUID brokerAccountId,
            String brokerName,
            @Nullable String provider,
            BigDecimal cashBalance,
            BigDecimal invested,
            BigDecimal currentValue,
            BigDecimal realized,
            BigDecimal intradayRealized,
            BigDecimal unrealized,
            BigDecimal totalCharges
    ) {}

    public record InstrumentTypeSummaryDto(
            InstrumentType type,
            BigDecimal invested,
            BigDecimal currentValue,
            BigDecimal percentage
    ) {}
}
