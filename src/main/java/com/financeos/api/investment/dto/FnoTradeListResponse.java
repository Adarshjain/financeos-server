package com.financeos.api.investment.dto;

import java.math.BigDecimal;
import java.util.List;

public record FnoTradeListResponse(
        List<FnoTradeResponse> trades,
        BigDecimal totalRealizedPnl
) {}
