package com.financeos.api.rules.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record UnverifyRuleRequest(
    @NotNull(message = "Transaction IDs list is required")
    @Size(max = 500, message = "Transaction IDs list cannot exceed 500 elements")
    List<@NotNull(message = "Transaction ID cannot be null") UUID> transactionIds
) {}
