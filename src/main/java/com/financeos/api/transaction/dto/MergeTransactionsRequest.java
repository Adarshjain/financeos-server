package com.financeos.api.transaction.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MergeTransactionsRequest(
    @NotNull(message = "keepId is required")
    UUID keepId,

    @NotNull(message = "deleteId is required")
    UUID deleteId
) {}
