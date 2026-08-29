package com.financeos.api.llm.dto;

import com.financeos.llm.LlmTaskGroup;

public record LlmTaskGroupDto(
        String code,
        String displayName,
        String description
) {
    public static LlmTaskGroupDto fromEnum(LlmTaskGroup group) {
        return new LlmTaskGroupDto(group.getCode(), group.getDisplayName(), group.getDescription());
    }
}
