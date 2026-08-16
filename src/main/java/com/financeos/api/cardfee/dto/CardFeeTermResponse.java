package com.financeos.api.cardfee.dto;

import com.financeos.domain.cardfee.CardFeeKind;
import com.financeos.domain.cardfee.FeeWaiverBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CardFeeTermResponse(
        UUID id,
        UUID accountId,
        CardFeeKind kind,
        LocalDate effectiveFrom,
        BigDecimal amount,
        BigDecimal gstRate,
        BigDecimal totalAmount,
        BigDecimal waiverSpendThreshold,
        FeeWaiverBasis waiverBasis,
        String note,
        LocalDate firstGovernedFeeYearStart
) {
}
