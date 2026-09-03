package com.financeos.api.llm.dto;

import org.springframework.lang.Nullable;

public record ModelCatalogEntryDto(
        String id,
        String label,
        String structuredOutput,
        boolean free,
        String trainsOnData,
        @Nullable String notes
) {}
