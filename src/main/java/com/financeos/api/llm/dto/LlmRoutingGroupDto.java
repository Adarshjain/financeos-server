package com.financeos.api.llm.dto;

import java.util.List;

public record LlmRoutingGroupDto(
        String group,
        String displayName,
        String description,
        boolean usingDefaults,
        List<RoutingEntryDto> entries
) {}
