package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CorporateActionResponse(
        UUID id,
        UUID instrumentId,
        CorporateActionType type,
        Integer ratioFrom,
        Integer ratioTo,
        LocalDate exDate,
        String notes,
        UUID targetInstrumentId,
        String targetInstrumentName,
        String targetInstrumentSymbol,
        BigDecimal costAllocationPct,
        Instant createdAt
) {
    public static CorporateActionResponse from(CorporateAction ca) {
        return new CorporateActionResponse(
                ca.getId(),
                ca.getInstrument().getId(),
                ca.getType(),
                ca.getRatioFrom(),
                ca.getRatioTo(),
                ca.getExDate(),
                ca.getNotes(),
                ca.getTargetInstrument() != null ? ca.getTargetInstrument().getId() : null,
                ca.getTargetInstrument() != null ? ca.getTargetInstrument().getName() : null,
                ca.getTargetInstrument() != null ? ca.getTargetInstrument().getSymbol() : null,
                ca.getCostAllocationPct(),
                ca.getCreatedAt()
        );
    }
}
