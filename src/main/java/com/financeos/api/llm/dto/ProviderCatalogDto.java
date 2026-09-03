package com.financeos.api.llm.dto;

import org.springframework.lang.Nullable;

import java.util.List;

public record ProviderCatalogDto(
        String id,
        String name,
        String type,
        @Nullable String defaultModel,
        List<ModelCatalogEntryDto> models
) {}
