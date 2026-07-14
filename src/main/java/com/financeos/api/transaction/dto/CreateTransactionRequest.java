package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.TransactionSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.financeos.core.validation.MccCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateTransactionRequest(
        @NotNull(message = "Account ID is required") UUID accountId,

        @NotNull(message = "Date is required") LocalDate date,

        @NotNull(message = "Amount is required") BigDecimal amount,

        String description,
        java.util.List<UUID> categoryIds,
        Boolean isTransactionUnderMonitoring,
        Boolean isTransactionExcluded,
        String monitoringReason,
        @MccCode String mcc) {
}
