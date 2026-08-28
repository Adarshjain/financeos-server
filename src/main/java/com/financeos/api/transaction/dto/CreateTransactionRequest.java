package com.financeos.api.transaction.dto;

import com.financeos.core.validation.MccCode;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateTransactionRequest(
        @NotNull(message = "Account ID is required") UUID accountId,
        UUID cardId,
        @NotNull(message = "Date is required") LocalDate date,
        @NotNull(message = "Amount is required") BigDecimal amount,
        String description,
        List<UUID> categoryIds,
        Boolean isTransactionUnderMonitoring,
        Boolean isTransactionExcluded,
        String monitoringReason,
        @MccCode String mcc,
        @jakarta.validation.Valid RewardDetailsRequest rewardDetails) {

    public CreateTransactionRequest(
            UUID accountId,
            LocalDate date,
            BigDecimal amount,
            String description,
            List<UUID> categoryIds,
            Boolean isTransactionUnderMonitoring,
            Boolean isTransactionExcluded,
            String monitoringReason,
            String mcc,
            RewardDetailsRequest rewardDetails) {
        this(accountId, null, date, amount, description, categoryIds, isTransactionUnderMonitoring, isTransactionExcluded, monitoringReason, mcc, rewardDetails);
    }
}
