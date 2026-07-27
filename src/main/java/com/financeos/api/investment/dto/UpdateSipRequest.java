package com.financeos.api.investment.dto;

import com.financeos.domain.investment.sip.SipFrequency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpdateSipRequest(
        @NotNull(message = "Amount is required") @Positive(message = "Amount must be positive") BigDecimal amount,
        @NotNull(message = "Frequency is required") SipFrequency frequency,
        Integer dayOfMonth,
        @NotNull(message = "Start date is required") LocalDate startDate,
        LocalDate endDate,
        @NotNull(message = "Active status is required") Boolean active,
        String notes
) {
}
