package com.financeos.api.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Takes back one apply: {@code transactionIds} = its {@code updatedIds}, {@code categoryKey}/{@code subcategoryKey} = the
 * pick it was given. With {@code ruleId}: {@code ruleCreated} deletes that rule; otherwise the previous target is put back.
 */
public record UndoUncategorizedRequest(
        @NotEmpty @Size(max = 10000) List<@NotNull UUID> transactionIds,
        @NotBlank String categoryKey,
        @Nullable String subcategoryKey,
        @Nullable UUID ruleId,
        boolean ruleCreated,
        @Nullable String previousRuleCategoryKey,
        @Nullable String previousRuleSubcategoryKey) {
}
