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
        /** The limit utilisation is measured against: the card's own, else the latest statement's. */
        @Nullable BigDecimal creditLimit,
        @Nullable BigDecimal availableCreditLimit,
        /** Live: what the card owes now ÷ creditLimit × 100 (one decimal); 0 when in credit, null without a limit. */
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
