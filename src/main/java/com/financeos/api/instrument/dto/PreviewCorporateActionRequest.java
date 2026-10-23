package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.corporateaction.CorporateActionType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Body of POST /instruments/{id}/corporate-actions/preview: the action as {@link CreateCorporateActionRequest}
 * plus, when editing a saved action, its id so the preview replays as if that action were replaced by this one.
 *
 * @param excludeActionId the caller's saved action on the instrument being edited; null previews a new action
 */
public record PreviewCorporateActionRequest(
        @NotNull(message = "Corporate action type is required") CorporateActionType type,
        @NotNull(message = "Ratio from is required") @Positive(message = "Ratio from must be positive") Integer ratioFrom,
        @NotNull(message = "Ratio to is required") @Positive(message = "Ratio to must be positive") Integer ratioTo,
        @NotNull(message = "Ex date is required") LocalDate exDate,
        String notes,
        UUID targetInstrumentId,
        BigDecimal costAllocationPct,
        BigDecimal fractionalCashInLieu,
        UUID excludeActionId
) {
    public CreateCorporateActionRequest toCreate() {
        return new CreateCorporateActionRequest(type, ratioFrom, ratioTo, exDate, notes, targetInstrumentId,
                costAllocationPct, fractionalCashInLieu);
    }
}
