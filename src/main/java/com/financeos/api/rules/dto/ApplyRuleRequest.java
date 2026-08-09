package com.financeos.api.rules.dto;

import java.util.List;
import java.util.UUID;

/**
 * Either an explicit selection of transaction ids (from the match-list checkboxes) or
 * all=true to apply to everything the rule's pattern currently matches.
 */
public record ApplyRuleRequest(
        List<UUID> transactionIds,
        Boolean all
) {}
