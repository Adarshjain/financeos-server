package com.financeos.api.llm.dto;

public record ModelCatalogEntryDto(
        String id,
        String label,
        String structuredOutput,
        boolean free,
        String trainsOnData,
        String notes
) {}
