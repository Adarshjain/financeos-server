package com.financeos.api.account.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CardCycleHistoryItemResponse(
        LocalDate periodEnd,
        @Nullable BigDecimal totalPurchases,
        @Nullable BigDecimal paymentsReceived,
        @Nullable BigDecimal financeCharges,
        @Nullable BigDecimal feesAndCharges,
        @Nullable BigDecimal rewardPointsBalance
) {}
