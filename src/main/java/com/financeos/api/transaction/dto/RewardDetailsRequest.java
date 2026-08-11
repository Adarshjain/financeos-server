package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.TransactionChannel;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reward-relevant transaction details. Overwrite semantics: when this object is
 * present on a create/update request, ALL six fields are applied (nulls clear);
 * when absent, the stored values are left untouched.
 */
public record RewardDetailsRequest(
        LocalDate settlementDate,
        @PositiveOrZero(message = "Instant discount cannot be negative") BigDecimal instantDiscount,
        @PositiveOrZero(message = "Convenience fee cannot be negative") BigDecimal convenienceFee,
        TransactionChannel channel,
        Boolean isEmi,
        Boolean isInternational) {
}
