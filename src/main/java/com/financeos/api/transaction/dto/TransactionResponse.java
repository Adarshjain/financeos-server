package com.financeos.api.transaction.dto;

import com.financeos.api.category.dto.CategoryResponse;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TransactionResponse(
                UUID id,
                UUID accountId,
                LocalDate date,
                BigDecimal amount,
                String description,
                List<CategoryResponse> categories,
                Boolean isTransactionExcluded,
                Boolean isTransactionUnderMonitoring,
                TransactionSource source,
                Map<String, Object> metadata,
                LocalDateTime createdAt) {
        public static TransactionResponse from(Transaction transaction) {
                // Convert internal representation (unsigned + type) to API representation
                // (signed)
                BigDecimal signedAmount = transaction.getType() == TransactionType.DEBIT
                                ? transaction.getAmount().negate()
                                : transaction.getAmount();

                // Map categories to CategoryResponse DTOs
                List<CategoryResponse> categoryResponses = transaction.getCategories().stream()
                                .map(CategoryResponse::from)
                                .toList();

                return new TransactionResponse(
                                transaction.getId(),
                                transaction.getAccount() != null ? transaction.getAccount().getId() : null,
                                transaction.getDate(),
                                signedAmount, // Return signed amount
                                transaction.getDescription(),
                                categoryResponses, // Return categories list
                                transaction.getIsTransactionExcluded(),
                                transaction.getIsTransactionUnderMonitoring(),
                                transaction.getSource(),
                                transaction.getMetadata(),
                                transaction.getCreatedAt());
        }
}
