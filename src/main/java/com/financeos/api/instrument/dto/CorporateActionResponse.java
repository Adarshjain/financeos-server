package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;

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
                ca.getCreatedAt()
        );
    }
}
