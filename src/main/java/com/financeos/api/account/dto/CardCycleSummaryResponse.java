package com.financeos.api.account.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CardCycleSummaryResponse(
        UUID statementId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal totalAmountDue,
        BigDecimal minimumAmountDue,
        LocalDate paymentDueDate,
        Long daysUntilDue,
        BigDecimal creditLimit,
        BigDecimal availableCreditLimit,
        BigDecimal utilizationPct,
        BigDecimal rewardPointsBalance,
        List<CardCycleHistoryItemResponse> history
) {
    public static CardCycleSummaryResponse empty() {
        return new CardCycleSummaryResponse(
                null, null, null, null, null, null, null, null, null, null, null, List.of()
        );
    }
}
