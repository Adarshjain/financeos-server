package com.financeos.api.search.dto;

import org.springframework.lang.Nullable;

/**
 * A fixed-taxonomy category or sub-category matched by name. {@code key} is the value the Transactions
 * filter uses: the "category" filter when {@code parentName} is null, else the "subcategory" filter.
 */
public record SearchCategoryHit(String key, String name, @Nullable String parentName) {
}
