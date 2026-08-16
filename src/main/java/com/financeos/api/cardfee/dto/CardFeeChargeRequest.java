package com.financeos.api.cardfee.dto;

import com.financeos.domain.cardfee.CardFeeKind;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record CardFeeChargeRequest(
        @NotNull(message = "Account ID is required") UUID accountId,
        @NotNull(message = "Kind is required") CardFeeKind kind,
        @NotNull(message = "Fee year start date is required") LocalDate feeYearStart,
        Boolean waived,
        BigDecimal overrideAmount,
        Set<UUID> transactionIds,
        String note
) {
}
