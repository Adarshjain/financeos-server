package com.financeos.api.category.dto;

import com.financeos.domain.category.CategoryCatalog;
import org.springframework.lang.Nullable;

public record SubcategoryRef(String key, String name) {

    @Nullable
    public static SubcategoryRef of(CategoryCatalog catalog, @Nullable String key) {
        return catalog.subcategory(key).map(s -> new SubcategoryRef(s.key(), s.name())).orElse(null);
    }
}
