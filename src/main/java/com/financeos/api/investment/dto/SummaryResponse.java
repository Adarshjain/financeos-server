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
        BigDecimal totalCharges,
        BigDecimal totalDividends,
        BigDecimal totalPnl,
        List<BrokerSummaryDto> byBroker,
        List<InstrumentTypeSummaryDto> byInstrumentType
) {
    public record BrokerSummaryDto(
            UUID brokerAccountId,
            String brokerName,
            String provider,
            BigDecimal cashBalance,
            BigDecimal invested,
            BigDecimal currentValue,
            BigDecimal realized,
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
