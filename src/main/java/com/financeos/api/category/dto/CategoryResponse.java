package com.financeos.api.category.dto;

import com.financeos.domain.category.Category;
import java.util.UUID;

public record CategoryResponse(
        UUID id,
        String name) {
    public static CategoryResponse from(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName());
    }
}
