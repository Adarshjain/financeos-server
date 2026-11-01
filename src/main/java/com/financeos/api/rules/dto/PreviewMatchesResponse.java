package com.financeos.api.rules.dto;

import org.springframework.data.domain.Page;

/**
 * matchCount: every transaction the pattern matches. userSetCount: those whose category the user set
 * (source USER), which applying the rule skips. matches: one page of them.
 */
public record PreviewMatchesResponse(
        long matchCount,
        long userSetCount,
        Page<RuleMatchTransactionResponse> matches
) {}
