package com.financeos.api.investment.dto;

import com.financeos.domain.investment.sip.SipFrequency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateSipRequest(
        @NotNull(message = "Broker account ID is required") UUID brokerAccountId,
        @NotNull(message = "Instrument ID is required") UUID instrumentId,
        @NotNull(message = "Amount is required") @Positive(message = "Amount must be positive") BigDecimal amount,
        @NotNull(message = "Frequency is required") SipFrequency frequency,
        Integer dayOfMonth,
        @NotNull(message = "Start date is required") LocalDate startDate,
        LocalDate endDate,
        Boolean active,
        String notes
) {
}
