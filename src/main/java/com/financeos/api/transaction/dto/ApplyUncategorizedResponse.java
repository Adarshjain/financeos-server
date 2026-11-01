package com.financeos.api.transaction.dto;

import org.springframework.lang.Nullable;

import java.util.UUID;

/** {@code skipped}: ids that are not the user's, already have a category, or whose direction the category does not fit. */
public record ApplyUncategorizedResponse(int updated, int skipped, @Nullable UUID ruleId) {
}
