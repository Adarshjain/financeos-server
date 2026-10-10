package com.financeos.api.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * One parameter a built-in widget accepts. {@code type} is {@code int} (bounded by min/max),
 * {@code uuid} ({@code ref} says what it points at: credit_card | account | loan), {@code enum}
 * (one of {@code options}) or {@code string_list} (at most {@code maxItems} strings, each matching
 * {@code itemPattern}).
 */
public record BuiltinParamResponse(
        String name,
        String type,
        Boolean required,
        @Nullable JsonNode defaultValue,
        @Nullable Integer min,
        @Nullable Integer max,
        /** uuid params: credit_card | account | loan; null otherwise. */
        @Nullable String ref,
        /** enum params: the accepted values; null otherwise. */
        @Nullable List<String> options,
        /** string_list params: the most items allowed; null otherwise. */
        @Nullable Integer maxItems,
        /** string_list params: the regex every item must match; null otherwise. */
        @Nullable String itemPattern) {
}
