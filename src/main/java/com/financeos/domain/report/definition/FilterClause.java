package com.financeos.domain.report.definition;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single filter predicate. Multiple clauses in a definition are AND-ed together.
 * {@code value} is a raw {@link JsonNode} because its shape varies by operator
 * (scalar, array, {@code {from,to}}, {@code {amount}}, or absent for valueless
 * relative-date operators such as {@code this_month}).
 */
public record FilterClause(
        String field,
        String operator,
        JsonNode value
) {}
