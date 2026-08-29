package com.financeos.api.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmRoutingDto(
        LlmRoutingGroupDto chat,
        @JsonProperty("default") LlmRoutingGroupDto defaultGroup
) {}
