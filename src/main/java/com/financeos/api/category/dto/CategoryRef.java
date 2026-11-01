package com.financeos.api.category.dto;

import com.financeos.domain.category.CategoryCatalog;
import org.springframework.lang.Nullable;

/** A transaction's or rule's category as the client draws it. */
public record CategoryRef(String key, String name, String icon) {

    @Nullable
    public static CategoryRef of(CategoryCatalog catalog, @Nullable String key) {
        return catalog.category(key).map(c -> new CategoryRef(c.key(), c.name(), c.icon())).orElse(null);
    }
}
