package com.financeos.api.investment.dto;

import com.financeos.domain.investment.InvestmentTransactionType;
import com.financeos.domain.investment.SettlementType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateInvestmentTransactionRequest(
        @NotNull(message = "Broker account ID is required") UUID brokerAccountId,
        @NotNull(message = "Instrument ID is required") UUID instrumentId,
        @NotNull(message = "Transaction type is required") InvestmentTransactionType type,
        // Optional: delivery | intraday. Defaults to delivery when null (manual entry).
        SettlementType settlementType,
        @NotNull(message = "Quantity is required") @Positive(message = "Quantity must be positive") BigDecimal quantity,
        @NotNull(message = "Price is required") @PositiveOrZero(message = "Price must be non-negative") BigDecimal price,
        @NotNull(message = "Trade date is required") LocalDate tradeDate,
        ItemizedChargesDto charges,
        String notes
) {
}
