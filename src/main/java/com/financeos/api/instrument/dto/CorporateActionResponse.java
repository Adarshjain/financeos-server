package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.InstrumentOverrides;
import com.financeos.domain.instrument.corporateaction.CorporateAction;
import com.financeos.domain.instrument.corporateaction.CorporateActionType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CorporateActionResponse(
        UUID id,
        UUID instrumentId,
        String instrumentName,
        @Nullable String instrumentSymbol,
        CorporateActionType type,
        Integer ratioFrom,
        Integer ratioTo,
        LocalDate exDate,
        @Nullable String notes,
        @Nullable UUID targetInstrumentId,
        @Nullable String targetInstrumentName,
        @Nullable String targetInstrumentSymbol,
        @Nullable BigDecimal costAllocationPct,
        @Nullable BigDecimal fractionalCashInLieu,
        Instant createdAt
) {
    public static CorporateActionResponse from(CorporateAction ca) {
        return from(ca, InstrumentOverrides.NONE);
    }

    /** The action with its instruments' names and symbols as {@code overrides}' user sees them. */
    public static CorporateActionResponse from(CorporateAction ca, InstrumentOverrides overrides) {
        return new CorporateActionResponse(
                ca.getId(),
                ca.getInstrument() != null ? ca.getInstrument().getId() : null,
                ca.getInstrument() != null ? overrides.name(ca.getInstrument()) : null,
                ca.getInstrument() != null ? overrides.symbol(ca.getInstrument()) : null,
                ca.getType(),
                ca.getRatioFrom(),
                ca.getRatioTo(),
                ca.getExDate(),
                ca.getNotes(),
                ca.getTargetInstrument() != null ? ca.getTargetInstrument().getId() : null,
                ca.getTargetInstrument() != null ? overrides.name(ca.getTargetInstrument()) : null,
                ca.getTargetInstrument() != null ? overrides.symbol(ca.getTargetInstrument()) : null,
                ca.getCostAllocationPct(),
                ca.getFractionalCashInLieu(),
                ca.getCreatedAt()
        );
    }
}
