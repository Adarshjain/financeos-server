package com.financeos.api.transaction.dto;

import org.springframework.lang.Nullable;

/** A category and optional sub-category, as keys of the fixed list. */
public record CategoryPick(String categoryKey, @Nullable String subcategoryKey) {
}
