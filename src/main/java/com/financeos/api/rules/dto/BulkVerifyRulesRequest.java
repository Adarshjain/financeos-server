package com.financeos.api.rules.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record BulkVerifyRulesRequest(
    @NotEmpty(message = "Rule IDs list cannot be empty")
    @Size(max = 500, message = "Rule IDs list cannot exceed 500 elements")
    List<@NotNull(message = "Rule ID cannot be null") UUID> ruleIds
) {}
