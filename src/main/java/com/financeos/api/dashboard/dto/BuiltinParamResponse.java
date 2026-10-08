package com.financeos.api.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

/** One parameter a built-in widget accepts ({@code type} is {@code int} or {@code uuid}). */
public record BuiltinParamResponse(
        String name,
        String type,
        Boolean required,
        @Nullable JsonNode defaultValue,
        @Nullable Integer min,
        @Nullable Integer max) {
}
