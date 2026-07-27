package com.financeos.api.investment.dto;

import com.financeos.domain.investment.dividend.DividendType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateDividendRequest(
        @NotNull(message = "Dividend type is required") DividendType type,
        @NotNull(message = "Amount is required") @PositiveOrZero(message = "Amount must be non-negative") BigDecimal amount,
        BigDecimal perUnit,
        BigDecimal tds,
        LocalDate exDate,
        @NotNull(message = "Pay date is required") LocalDate payDate,
        String notes
) {
}
