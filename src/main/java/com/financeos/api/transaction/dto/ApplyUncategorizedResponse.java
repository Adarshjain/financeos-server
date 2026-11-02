package com.financeos.api.transaction.dto;

import org.springframework.lang.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * {@code skipped}: ids that are not the user's, already have a category, or whose direction the category does not fit.
 * What the client sends back to undo: {@code updatedIds} (the rows actually categorised), {@code ruleCreated} (the
 * rule did not exist before this call) and, when an existing rule was re-pointed, the target it had before.
 */
public record ApplyUncategorizedResponse(int updated, int skipped, @Nullable UUID ruleId, List<UUID> updatedIds,
                                         boolean ruleCreated, @Nullable String previousRuleCategoryKey,
                                         @Nullable String previousRuleSubcategoryKey) {
}
