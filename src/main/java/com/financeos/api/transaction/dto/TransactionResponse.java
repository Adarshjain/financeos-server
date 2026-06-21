package com.financeos.api.transaction.dto;

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
                boolean isTransactionExcluded,
                Instant createdAt,
                BigDecimal balance) {
        public static TransactionResponse from(Transaction transaction) {
                return from(transaction, null);
        }

        public static TransactionResponse from(Transaction transaction, BigDecimal balance) {
                // Convert internal representation (unsigned + type) to API representation
                // (signed)
                BigDecimal signedAmount = transaction.getType() == TransactionType.DEBIT
                                ? transaction.getAmount().negate()
                                : transaction.getAmount();

                java.util.List<com.financeos.api.category.dto.CategoryResponse> categoryResponses = transaction
                                .getCategories().stream()
                                .map(tc -> com.financeos.api.category.dto.CategoryResponse.from(tc.getCategory()))
                                .toList();

                return new TransactionResponse(
                                transaction.getId(),
                                transaction.getAccount() != null ? transaction.getAccount().getId() : null,
                                transaction.getDate(),
                                signedAmount, // Return signed amount
                                transaction.getDescription(),
                                transaction.getSourcedDescription(),
                                categoryResponses,
                                transaction.getSource(),
                                transaction.isTransactionUnderMonitoring(),
                                transaction.isTransactionExcluded(),
                                transaction.getCreatedAt(),
                                balance);
        }
}
