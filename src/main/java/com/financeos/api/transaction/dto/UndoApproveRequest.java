package com.financeos.api.transaction.dto;

import com.financeos.domain.categorization.CategorizationService.ClearedTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** approved / rules[].restore are the approvedTransactions / verifiedRules[].clearedTransactions of a batch-review response. */
public record UndoApproveRequest(
    @Size(max = 500, message = "Approved list cannot exceed 500 elements")
    List<@NotNull(message = "Approved entry cannot be null") @Valid ClearedTransaction> approved,

    @Size(max = 50, message = "Rules list cannot exceed 50 elements")
    List<@NotNull(message = "Rule entry cannot be null") @Valid RuleRestore> rules
) {
    public record RuleRestore(
        @NotNull(message = "Rule ID is required") UUID ruleId,
        @Size(max = 20000, message = "Restore list cannot exceed 20000 elements")
        List<@NotNull(message = "Restore entry cannot be null") @Valid ClearedTransaction> restore
    ) {}
}
