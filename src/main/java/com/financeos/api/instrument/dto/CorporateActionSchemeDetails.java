package com.financeos.api.instrument.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.lang.Nullable;

/**
 * What the company's own exchange notices say about a demerger or merger, so the record dialog can open filled in.
 * Null on its holder when no notice has been read yet.
 *
 * @param children      the child companies (demerger) or the acquirer (merger); empty when none could be named
 * @param parentCostPct the parent's percentage of its cost of acquisition after the scheme; null when unknown
 * @param costKind      {@code NOTICE} (published by the company) or {@code ESTIMATE} (from the prices around the ex-date); null with no percentage
 * @param costSource    the notice the split was read from; null for an estimate or no split
 * @param missing       why a value is blank: CHILD_NOT_NAMED, RATIO_NOT_FOUND, COST_NOT_PUBLISHED_YET, COST_UNCLEAR,
 *                      NOTICE_SCANNED, NOTICES_DISAGREE, NO_NOTICES, NOTICES_UNAVAILABLE
 * @param checkedAt     when the notices were last read
 * @param holdingsCheck per broker account that held the parent: do the user's holdings match the notices? Empty until checked
 */
public record CorporateActionSchemeDetails(
        List<CorporateActionSchemeChild> children,
        @Nullable BigDecimal parentCostPct,
        @Nullable String costKind,
        @Nullable CorporateActionSchemeSource costSource,
        List<String> missing,
        Instant checkedAt,
        List<CorporateActionSchemeHoldingsCheck> holdingsCheck
) {
}
