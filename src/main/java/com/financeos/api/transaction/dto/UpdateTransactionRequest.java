package com.financeos.api.transaction.dto;

import com.financeos.core.validation.MccCode;
import com.financeos.domain.transaction.ReviewType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UpdateTransactionRequest(
        UUID accountId,
        UUID cardId,
        @NotNull(message = "Date is required") LocalDate date,
        @NotNull(message = "Amount is required") BigDecimal amount,
        String description,
        List<UUID> categoryIds,
        Boolean isTransactionUnderMonitoring,
        Boolean isTransactionExcluded,
        ReviewType reviewType,
        String monitoringReason,
        @MccCode String mcc,
        @jakarta.validation.Valid RewardDetailsRequest rewardDetails) {

    public UpdateTransactionRequest(
            LocalDate date,
            BigDecimal amount,
            String description,
            List<UUID> categoryIds,
            Boolean isTransactionUnderMonitoring,
            Boolean isTransactionExcluded,
            ReviewType reviewType,
            String monitoringReason,
            String mcc,
            RewardDetailsRequest rewardDetails) {
        this(null, null, date, amount, description, categoryIds, isTransactionUnderMonitoring, isTransactionExcluded, reviewType, monitoringReason, mcc, rewardDetails);
    }

    public UpdateTransactionRequest(
            LocalDate date,
            BigDecimal amount,
            String description,
            List<UUID> categoryIds,
            Boolean isTransactionUnderMonitoring,
            Boolean isTransactionExcluded,
            ReviewType reviewType,
            String monitoringReason,
            String mcc) {
        this(null, null, date, amount, description, categoryIds, isTransactionUnderMonitoring, isTransactionExcluded, reviewType, monitoringReason, mcc, null);
    }
}
