package com.financeos.api.cardfee.dto;

import com.financeos.domain.cardfee.CardFeeKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

public record CardFeeChargeResponse(
        UUID id,
        UUID accountId,
        CardFeeKind kind,
        LocalDate feeYearStart,
        Boolean waived,
        BigDecimal overrideAmount,
        Set<UUID> transactionIds,
        String note
) {
}
