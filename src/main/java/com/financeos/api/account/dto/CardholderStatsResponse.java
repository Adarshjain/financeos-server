package com.financeos.api.account.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Usage numbers for one cardholder; the financial year and the billing cycle are the ones containing today. */
public record CardholderStatsResponse(
        UUID cardholderId,
        BigDecimal spendThisYear,
        LocalDate yearFrom,
        LocalDate yearTo,
        @Nullable LocalDate lastUsedOn,
        BigDecimal currentCycleSpend,
        @Nullable LocalDate cycleFrom,
        @Nullable LocalDate cycleTo,
        long transactionCount,
        List<CardStats> cards
) {
    public record CardStats(
            UUID cardId,
            long transactionCount,
            @Nullable LocalDate firstUsedOn,
            @Nullable LocalDate lastUsedOn
    ) {}
}
