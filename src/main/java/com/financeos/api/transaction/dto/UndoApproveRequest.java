package com.financeos.api.transaction.dto;

import com.financeos.domain.categorization.CategorizationService.ClearedTransaction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** approved is the approvedTransactions of a batch-review response. */
public record UndoApproveRequest(
    @Size(max = 500, message = "Approved list cannot exceed 500 elements")
    List<@NotNull(message = "Approved entry cannot be null") @Valid ClearedTransaction> approved
) {
}
