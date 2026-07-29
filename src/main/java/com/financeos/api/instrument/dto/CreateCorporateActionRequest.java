package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateCorporateActionRequest(
        @NotNull(message = "Corporate action type is required") CorporateActionType type,
        @NotNull(message = "Ratio from is required") @Positive(message = "Ratio from must be positive") Integer ratioFrom,
        @NotNull(message = "Ratio to is required") @Positive(message = "Ratio to must be positive") Integer ratioTo,
        @NotNull(message = "Ex date is required") LocalDate exDate,
        String notes,
        UUID targetInstrumentId,
        BigDecimal costAllocationPct
) {
}
