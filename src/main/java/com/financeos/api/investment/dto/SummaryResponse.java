package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.InstrumentType;

import java.math.BigDecimal;
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
        Double xirr,
        BigDecimal absoluteReturnPercent,
        List<BrokerSummaryDto> byBroker,
        List<InstrumentTypeSummaryDto> byInstrumentType,
        BigDecimal totalFnoRealized
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
            Double xirr,
            BigDecimal absoluteReturnPercent,
            List<BrokerSummaryDto> byBroker,
            List<InstrumentTypeSummaryDto> byInstrumentType
    ) {
        this(totalInvested, totalCurrentValue, totalUnrealized, totalUnrealizedPercent, totalRealized, totalIntradayRealized, totalCharges, totalDividends, totalPnl, xirr, absoluteReturnPercent, byBroker, byInstrumentType, BigDecimal.ZERO);
    }

    public record BrokerSummaryDto(
            UUID brokerAccountId,
            String brokerName,
            String provider,
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
