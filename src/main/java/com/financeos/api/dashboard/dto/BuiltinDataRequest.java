package com.financeos.api.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

/** Body for running a template built-in: the widget's params (may be null/empty for defaults). */
public record BuiltinDataRequest(@Nullable JsonNode params) {
}
