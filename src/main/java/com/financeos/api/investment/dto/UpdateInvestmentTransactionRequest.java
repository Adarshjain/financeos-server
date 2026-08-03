package com.financeos.api.investment.dto;

import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateInvestmentTransactionRequest(
        @NotNull(message = "Transaction type is required") InvestmentTransactionType type,
        // Optional: delivery | intraday. Null leaves the existing value unchanged.
        SettlementType settlementType,
        @NotNull(message = "Quantity is required") @Positive(message = "Quantity must be positive") BigDecimal quantity,
        @NotNull(message = "Price is required") @PositiveOrZero(message = "Price must be non-negative") BigDecimal price,
        @NotNull(message = "Trade date is required") LocalDate tradeDate,
        ItemizedChargesDto charges,
        String notes
) {
}
