package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.TransactionSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record UpdateTransactionRequest(
                @NotNull(message = "Date is required") LocalDate date,

                @NotNull(message = "Amount is required") BigDecimal amount,

                String description,
                List<UUID> categoryIds,
                Boolean isTransactionUnderMonitoring,
                Boolean isTransactionExcluded) {
}
