package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Date granularity used when grouping by a date dimension. */
public enum Granularity {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
    QUARTER("quarter"),
    YEAR("year");

    private final String json;

    Granularity(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static Granularity from(String value) {
        if (value != null) {
            for (Granularity g : values()) {
                if (g.json.equalsIgnoreCase(value) || g.name().equalsIgnoreCase(value)) {
                    return g;
                }
            }
        }
        throw new IllegalArgumentException("Unknown granularity: " + value);
    }
}
