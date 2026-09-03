package com.financeos.api.account.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CardCycleSummaryResponse(
        @Nullable UUID statementId,
        @Nullable LocalDate periodStart,
        @Nullable LocalDate periodEnd,
        @Nullable BigDecimal totalAmountDue,
        @Nullable BigDecimal minimumAmountDue,
        @Nullable LocalDate paymentDueDate,
        @Nullable Long daysUntilDue,
        @Nullable BigDecimal creditLimit,
        @Nullable BigDecimal availableCreditLimit,
        @Nullable BigDecimal utilizationPct,
        @Nullable BigDecimal rewardPointsBalance,
        List<CardCycleHistoryItemResponse> history
) {
    public static CardCycleSummaryResponse empty() {
        return new CardCycleSummaryResponse(
                null, null, null, null, null, null, null, null, null, null, null, List.of()
        );
    }
}
