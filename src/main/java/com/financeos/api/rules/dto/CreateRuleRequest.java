package com.financeos.api.rules.dto;

import jakarta.validation.constraints.NotBlank;
import com.financeos.core.validation.MccCode;
import org.springframework.lang.Nullable;

public record CreateRuleRequest(
        @NotBlank String merchantKey,
        /** CONTAINS (default), STARTS_WITH, EXACT or REGEX. */
        String matchType,
        String displayName,
        @NotBlank String categoryKey,
        @Nullable String subcategoryKey,
        @Nullable @MccCode String mcc
) {}
