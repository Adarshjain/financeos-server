package com.financeos.domain.report.definition;

/**
 * Reference to a dimension field, with an optional granularity for date dimensions.
 * {@code granularity} is required when {@code field} is {@code date}; null otherwise.
 * Validation is enforced by the definition validator, not here.
 */
public record DimensionRef(
        String field,
        Granularity granularity
) {}
