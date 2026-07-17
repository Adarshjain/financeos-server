package com.financeos.api.account.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CardCycleHistoryItemResponse(
        LocalDate periodEnd,
        BigDecimal totalPurchases,
        BigDecimal paymentsReceived,
        BigDecimal financeCharges,
        BigDecimal feesAndCharges,
        BigDecimal rewardPointsBalance
) {}
