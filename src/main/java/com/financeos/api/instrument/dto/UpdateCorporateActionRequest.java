package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record UpdateCorporateActionRequest(
        @NotNull(message = "Corporate action type is required") CorporateActionType type,
        @NotNull(message = "Ratio from is required") @Positive(message = "Ratio from must be positive") Integer ratioFrom,
        @NotNull(message = "Ratio to is required") @Positive(message = "Ratio to must be positive") Integer ratioTo,
        @NotNull(message = "Ex date is required") LocalDate exDate,
        String notes
) {
}
