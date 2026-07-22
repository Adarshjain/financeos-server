package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponse(
                UUID id,
                UUID accountId,
                LocalDate date,
                BigDecimal amount,
                String description,
                String sourcedDescription,
                java.util.List<com.financeos.api.category.dto.CategoryResponse> categories,
                TransactionSource source,
                boolean isTransactionUnderMonitoring,
                String monitoringReason,
                boolean isTransactionExcluded,
                Instant createdAt,
                Instant updatedAt,
                Instant reviewedAt,
                BigDecimal balance,
                ReviewType reviewType,
                java.util.List<com.financeos.domain.transaction.ReviewReason> reviewReasons,
                UUID appliedRuleId,
                String mcc,
                java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary> links) {

        public static TransactionResponse from(Transaction transaction) {
                return from(transaction, null, java.util.Collections.emptyMap());
        }

        public static TransactionResponse from(Transaction transaction, BigDecimal balance) {
                return from(transaction, balance, java.util.Collections.emptyMap());
        }

        public static TransactionResponse from(Transaction transaction, BigDecimal balance,
                        java.util.Map<UUID, java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary>> linkMap) {
                // Convert internal representation (unsigned + type) to API representation
                // (signed)
                BigDecimal signedAmount = transaction.getType() == TransactionType.DEBIT
                                ? transaction.getAmount().negate()
                                : transaction.getAmount();

                java.util.List<com.financeos.api.category.dto.CategoryResponse> categoryResponses = transaction
                                .getCategories().stream()
                                .map(tc -> com.financeos.api.category.dto.CategoryResponse.from(tc.getCategory()))
                                .toList();

                java.util.List<com.financeos.domain.transaction.ReviewReason> reviewReasonsList = transaction.getReviewReasons() != null
                                ? new java.util.ArrayList<>(transaction.getReviewReasons())
                                : java.util.Collections.emptyList();

                java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary> transactionLinks = linkMap != null
                                && linkMap.containsKey(transaction.getId())
                                                ? linkMap.get(transaction.getId())
                                                : java.util.Collections.emptyList();

                return new TransactionResponse(
                                transaction.getId(),
                                transaction.getAccount().getId(),
                                transaction.getDate(),
                                signedAmount, // Return signed amount
                                transaction.getDescription(),
                                transaction.getSourcedDescription(),
                                categoryResponses,
                                transaction.getSource(),
                                transaction.isTransactionUnderMonitoring(),
                                transaction.getMonitoringReason(),
                                transaction.isTransactionExcluded(),
                                transaction.getCreatedAt(),
                                transaction.getUpdatedAt(),
                                transaction.getReviewedAt(),
                                balance,
                                transaction.getReviewType(),
                                reviewReasonsList,
                                transaction.getAppliedRule() != null ? transaction.getAppliedRule().getId() : null,
                                transaction.getMcc(),
                                transactionLinks);
        }
}
