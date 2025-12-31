package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.TransactionSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record CreateTransactionRequest(
        UUID accountId,

        @NotNull(message = "Date is required")
        LocalDate date,

        @NotNull(message = "Amount is required")
        BigDecimal amount,

        @NotBlank(message = "Description is required")
        String description,

        String category,

        String subcategory,

        String spentFor,

        @NotNull(message = "Source is required")
        TransactionSource source,

        Map<String, Object> metadata
) {}

