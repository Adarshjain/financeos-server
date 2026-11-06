package com.financeos.api.transaction.dto;

import com.financeos.core.validation.MccCode;
import com.financeos.domain.transaction.ReviewReason;
import com.financeos.domain.transaction.ReviewType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record UpdateTransactionRequest(
        UUID accountId,
        UUID cardId,
        @NotNull(message = "Date is required") LocalDate date,
        /** Signed rupee amount; required, except null on a foreign charge whose rupee amount is still unknown (kept unknown). */
        BigDecimal amount,
        String description,
        List<UUID> tagIds,
        /**
         * The category to set when present; changing it from the stored one makes it the user's choice.
         * Absent (null) leaves the stored category alone; clear it with PUT /transactions/{id}/category.
         */
        String categoryKey,
        String subcategoryKey,
        Boolean isTransactionUnderMonitoring,
        Boolean isTransactionExcluded,
        ReviewType reviewType,
        String monitoringReason,
        @MccCode String mcc,
        @jakarta.validation.Valid RewardDetailsRequest rewardDetails,
        Set<ReviewReason> reviewReasons) {

    public UpdateTransactionRequest(
            UUID accountId,
            UUID cardId,
            LocalDate date,
            BigDecimal amount,
            String description,
            List<UUID> tagIds,
            Boolean isTransactionUnderMonitoring,
            Boolean isTransactionExcluded,
            ReviewType reviewType,
            String monitoringReason,
            String mcc,
            RewardDetailsRequest rewardDetails) {
        this(accountId, cardId, date, amount, description, tagIds, null, null, isTransactionUnderMonitoring, isTransactionExcluded, reviewType, monitoringReason, mcc, rewardDetails, null);
    }

    public UpdateTransactionRequest(
            LocalDate date,
            BigDecimal amount,
            String description,
            List<UUID> tagIds,
            Boolean isTransactionUnderMonitoring,
            Boolean isTransactionExcluded,
            ReviewType reviewType,
            String monitoringReason,
            String mcc,
            RewardDetailsRequest rewardDetails) {
        this(null, null, date, amount, description, tagIds, null, null, isTransactionUnderMonitoring, isTransactionExcluded, reviewType, monitoringReason, mcc, rewardDetails, null);
    }

    public UpdateTransactionRequest(
            LocalDate date,
            BigDecimal amount,
            String description,
            List<UUID> tagIds,
            Boolean isTransactionUnderMonitoring,
            Boolean isTransactionExcluded,
            ReviewType reviewType,
            String monitoringReason,
            String mcc) {
        this(null, null, date, amount, description, tagIds, null, null, isTransactionUnderMonitoring, isTransactionExcluded, reviewType, monitoringReason, mcc, null, null);
    }
}
