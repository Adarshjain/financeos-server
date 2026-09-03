package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponse(
                UUID id,
                UUID accountId,
                @Nullable UUID cardId,
                @Nullable String cardLabel,
                /** The plastic the spend happened on — the user-facing requirement is "account name + last 4". */
                @Nullable String cardLast4,
                LocalDate date,
                BigDecimal amount,
                @Nullable String description,
                @Nullable String sourcedDescription,
                java.util.List<com.financeos.api.category.dto.CategoryResponse> categories,
                TransactionSource source,
                boolean isTransactionUnderMonitoring,
                @Nullable String monitoringReason,
                boolean isTransactionExcluded,
                Instant createdAt,
                Instant updatedAt,
                @Nullable Instant reviewedAt,
                @Nullable BigDecimal balance,
                @Nullable ReviewType reviewType,
                java.util.List<com.financeos.domain.transaction.ReviewReason> reviewReasons,
                @Nullable UUID appliedRuleId,
                @Nullable String mcc,
                @Nullable LocalDate settlementDate,
                @Nullable BigDecimal instantDiscount,
                @Nullable BigDecimal convenienceFee,
                @Nullable com.financeos.domain.transaction.TransactionChannel channel,
                @Nullable Boolean isEmi,
                @Nullable Boolean isInternational,
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

                UUID cardId = transaction.getCard() != null ? transaction.getCard().getId() : null;
                String cardLabel = transaction.getCard() != null && transaction.getCard().getCardholder() != null
                                ? transaction.getCard().getCardholder().getDisplayName()
                                : null;
                String cardLast4 = transaction.getCard() != null ? transaction.getCard().getLast4() : null;

                return new TransactionResponse(
                                transaction.getId(),
                                transaction.getAccount().getId(),
                                cardId,
                                cardLabel,
                                cardLast4,
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
                                transaction.getSettlementDate(),
                                transaction.getInstantDiscount(),
                                transaction.getConvenienceFee(),
                                transaction.getChannel(),
                                transaction.getIsEmi(),
                                transaction.getIsInternational(),
                                transactionLinks);
        }
}
