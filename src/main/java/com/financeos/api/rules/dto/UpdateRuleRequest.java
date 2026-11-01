package com.financeos.api.rules.dto;

import com.financeos.core.validation.MccCode;
import org.springframework.lang.Nullable;

/**
 * Absent fields are left alone. categoryKey, when present, sets the category and sub-category together
 * (a null subcategoryKey then means no sub-category); subcategoryKey alone is ignored.
 */
public record UpdateRuleRequest(
        String merchantKey,
        String matchType,
        String displayName,
        @Nullable String categoryKey,
        @Nullable String subcategoryKey,
        @Nullable @MccCode String mcc
) {}
