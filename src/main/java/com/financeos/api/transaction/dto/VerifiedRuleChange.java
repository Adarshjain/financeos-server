package com.financeos.api.transaction.dto;

import java.util.List;
import java.util.UUID;

/** A rule a batch review verified, and the other transactions whose CATEGORY_UNVERIFIED it cleared. */
public record VerifiedRuleChange(UUID ruleId, List<UUID> clearedTransactionIds) {}
