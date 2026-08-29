package com.financeos.api.llm.dto;

import java.util.List;

public record ProviderCatalogDto(
        String id,
        String name,
        String type,
        String defaultModel,
        List<ModelCatalogEntryDto> models
) {}
