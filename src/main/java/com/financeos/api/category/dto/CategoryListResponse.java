package com.financeos.api.category.dto;

import com.financeos.domain.category.CategoryCatalog;
import com.financeos.domain.category.CategoryDirection;
import org.springframework.lang.Nullable;

import java.util.List;

/** The whole fixed category list with sub-categories. Static; changes only with a server release. */
public record CategoryListResponse(List<Category> categories) {

    public record Category(
            String key,
            String name,
            CategoryDirection direction,
            @Nullable Integer outOrder,
            @Nullable Integer inOrder,
            String icon,
            boolean excludedByDefault,
            List<Sub> subs) {
    }

    public record Sub(String key, String name, CategoryDirection direction, int sortOrder) {
    }

    public static CategoryListResponse from(CategoryCatalog catalog) {
        return new CategoryListResponse(catalog.all().stream()
                .map(c -> new Category(c.key(), c.name(), c.direction(), c.outOrder(), c.inOrder(), c.icon(),
                        c.excludedByDefault(),
                        c.subs().stream().map(s -> new Sub(s.key(), s.name(), s.direction(), s.sortOrder())).toList()))
                .toList());
    }
}
