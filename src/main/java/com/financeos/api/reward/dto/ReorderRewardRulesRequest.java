package com.financeos.api.reward.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Complete priority order for an account's rules — first id gets the highest priority. */
public record ReorderRewardRulesRequest(
        @NotNull(message = "Account ID is required") UUID accountId,
        @NotEmpty(message = "orderedIds is required") List<UUID> orderedIds) {
}
