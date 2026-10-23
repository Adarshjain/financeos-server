package com.financeos.api.rules.dto;

import java.util.List;
import java.util.UUID;

/** skippedTransactionIds: requested rows left as they are (edited since the verify, moved off the rule, or not the caller's). */
public record UnverifyRuleResponse(List<UUID> restoredTransactionIds, List<UUID> skippedTransactionIds) {}
