package com.financeos.api.search.dto;

import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * An EPF or NPS hit. EPF is one hit (no accountId; subtitle = current employer). NPS is one hit per account
 * (title "NPS", or "NPS Vatsalya" for a child's Vatsalya account); only the stored last 4 of the PRAN is shown,
 * the holder name only for a child, and matchedScheme only when the match came from a scheme name.
 */
public record SearchRetirementHit(
        String kind,
        @Nullable UUID accountId,
        String title,
        @Nullable String subtitle,
        @Nullable String pranLast4,
        @Nullable String holderName,
        @Nullable String matchedScheme) {
}
