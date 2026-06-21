package com.financeos.domain.report.definition;

import com.financeos.domain.report.datasource.Aggregation;

/**
 * Reference to a measure field together with the aggregation to apply.
 */
public record MeasureRef(
        String field,
        Aggregation aggregation
) {}
