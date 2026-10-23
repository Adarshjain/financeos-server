package com.financeos.api.rules.dto;

import com.financeos.domain.categorization.CategorizationService.ClearedTransaction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * transactionIds: rows that only get CATEGORY_UNVERIFIED put back. restore (optional): the
 * clearedTransactions a verify returned, put back exactly as they were unless edited since.
 */
public record UnverifyRuleRequest(
    @NotNull(message = "Transaction IDs list is required")
    @Size(max = 500, message = "Transaction IDs list cannot exceed 500 elements")
    List<@NotNull(message = "Transaction ID cannot be null") UUID> transactionIds,

    @Size(max = 5000, message = "Restore list cannot exceed 5000 elements")
    List<@NotNull(message = "Restore entry cannot be null") ClearedTransaction> restore
) {}
