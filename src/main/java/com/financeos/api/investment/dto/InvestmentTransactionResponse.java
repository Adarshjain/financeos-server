package com.financeos.api.investment.dto;

import com.financeos.domain.investment.InvestmentTransaction;
import com.financeos.domain.investment.InvestmentTransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record InvestmentTransactionResponse(
        UUID id,
        UUID accountId,
        InvestmentTransactionType type,
        BigDecimal quantity,
        BigDecimal price,
        LocalDate date,
        Map<String, Object> metadata,
        LocalDateTime createdAt) {
    public static InvestmentTransactionResponse from(InvestmentTransaction transaction) {
        return new InvestmentTransactionResponse(
                transaction.getId(),
                transaction.getAccount() != null ? transaction.getAccount().getId() : null,
                transaction.getType(),
                transaction.getQuantity(),
                transaction.getPrice(),
                transaction.getDate(),
                transaction.getMetadata(),
                transaction.getCreatedAt());
    }
}
