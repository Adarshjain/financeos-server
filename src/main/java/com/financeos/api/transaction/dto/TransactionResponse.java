package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record TransactionResponse(
                UUID id,
                UUID accountId,
                LocalDate date,
                BigDecimal amount,
                String description,
                String category,
                String subcategory,
                String spentFor,
                TransactionSource source,
                Map<String, Object> metadata,
                Instant createdAt) {
        public static TransactionResponse from(Transaction transaction) {
                // Convert internal representation (unsigned + type) to API representation
                // (signed)
                BigDecimal signedAmount = transaction.getType() == TransactionType.DEBIT
                                ? transaction.getAmount().negate()
                                : transaction.getAmount();

                return new TransactionResponse(
                                transaction.getId(),
                                transaction.getAccount() != null ? transaction.getAccount().getId() : null,
                                transaction.getDate(),
                                signedAmount, // Return signed amount
                                transaction.getDescription(),
                                transaction.getCategory(),
                                transaction.getSubcategory(),
                                transaction.getSpentFor(),
                                transaction.getSource(),
                                transaction.getMetadata(),
                                transaction.getCreatedAt());
        }
}
