package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsSchemeTier;
import com.financeos.domain.nps.NpsVariant;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A catalog scheme with its latest NAV; {@code instrumentId} serves the existing instrument price-history endpoint. */
public record NpsSchemeResponse(
        String schemeCode,
        UUID instrumentId,
        String name,
        @Nullable String pfmCode,
        @Nullable String pfmName,
        @Nullable String assetClass,
        @Nullable NpsSchemeTier tier,
        NpsVariant variant,
        @Nullable BigDecimal nav,
        @Nullable LocalDate navDate) {
}
