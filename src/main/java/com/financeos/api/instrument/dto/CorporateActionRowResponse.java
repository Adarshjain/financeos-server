package com.financeos.api.instrument.dto;

import com.financeos.domain.instrument.corporateaction.CorporateActionType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of GET /corporate-actions/page: the action as {@link CorporateActionResponse} plus its effect on
 * the caller's holding of the instrument.
 *
 * @param quantityBefore the caller's open quantity of the instrument (all brokers) just before the ex-date;
 *                       null when they hold none of it
 * @param quantityAfter  the same quantity once the action (and anything else on that ex-date) applies; null
 *                       when they hold none of it
 */
public record CorporateActionRowResponse(
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
        Instant createdAt,
        @Nullable BigDecimal quantityBefore,
        @Nullable BigDecimal quantityAfter
) {
    public static CorporateActionRowResponse of(CorporateActionResponse r, @Nullable BigDecimal before,
                                                @Nullable BigDecimal after) {
        return new CorporateActionRowResponse(r.id(), r.instrumentId(), r.instrumentName(), r.instrumentSymbol(),
                r.type(), r.ratioFrom(), r.ratioTo(), r.exDate(), r.notes(), r.targetInstrumentId(),
                r.targetInstrumentName(), r.targetInstrumentSymbol(), r.costAllocationPct(),
                r.fractionalCashInLieu(), r.createdAt(), before, after);
    }
}
