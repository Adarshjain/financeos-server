package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The comparison window relative to the primary date range. */
public enum ComparisonPeriod {
    PREVIOUS_PERIOD("previous_period");

    private final String json;

    ComparisonPeriod(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static ComparisonPeriod from(String value) {
        if (value != null) {
            for (ComparisonPeriod p : values()) {
                if (p.json.equalsIgnoreCase(value) || p.name().equalsIgnoreCase(value)) {
                    return p;
                }
            }
        }
        throw new IllegalArgumentException("Unknown comparisonPeriod: " + value);
    }
}
