package com.financeos.api.cardfee.dto;

import com.financeos.domain.cardfee.CardFeeKind;
import com.financeos.domain.cardfee.FeeWaiverBasis;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CardFeeTermRequest(
        @NotNull(message = "Account ID is required") UUID accountId,
        @NotNull(message = "Kind is required") CardFeeKind kind,
        @NotNull(message = "Effective from date is required") LocalDate effectiveFrom,
        BigDecimal amount,
        BigDecimal gstRate,
        BigDecimal waiverSpendThreshold,
        FeeWaiverBasis waiverBasis,
        String note
) {
}
