package com.financeos.api.rules.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A rule definition to test against the user's transactions — usable before the rule
 * is saved. matchType defaults to MERCHANT_KEY when omitted.
 */
public record PreviewMatchesRequest(
        @NotBlank String merchantKey,
        String matchType
) {}
