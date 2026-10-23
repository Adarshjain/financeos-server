package com.financeos.api.transaction.dto;

import com.financeos.domain.categorization.CategorizationService.ClearedTransaction;
import java.util.List;
import java.util.UUID;

/**
 * A rule a batch review verified, and the other transactions whose CATEGORY_UNVERIFIED it cleared
 * (clearedTransactions carries their prior state and stamp, for undo-approve).
 */
public record VerifiedRuleChange(UUID ruleId, List<UUID> clearedTransactionIds, List<ClearedTransaction> clearedTransactions) {
    public VerifiedRuleChange(UUID ruleId, List<UUID> clearedTransactionIds) {
        this(ruleId, clearedTransactionIds, List.of());
    }
}
