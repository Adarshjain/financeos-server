package com.financeos.api.investment.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record InvestmentPositionResponse(
        List<Position> positions
) {
    public record Position(
            UUID accountId,
            String instrumentCode,
            String accountName,
            BigDecimal quantity,
            BigDecimal averageCost,
            BigDecimal totalCost,
            BigDecimal lastTradedPrice,
            BigDecimal currentValue,
            BigDecimal unrealizedGainLoss,
            BigDecimal unrealizedGainLossPercent
    ) {}
}

