package com.financeos.api.investment.dto;

import com.financeos.domain.investment.InvestmentTransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record CreateInvestmentTransactionRequest(
        @NotNull(message = "Account ID is required")
        UUID accountId,

        @NotNull(message = "Transaction type is required")
        InvestmentTransactionType type,

        @NotNull(message = "Quantity is required")
        @DecimalMin(value = "0", inclusive = false, message = "Quantity must be positive")
        BigDecimal quantity,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0", message = "Price must be non-negative")
        BigDecimal price,

        @NotNull(message = "Date is required")
        LocalDate date,

        Map<String, Object> metadata
) {}

