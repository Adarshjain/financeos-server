package com.financeos.domain.report.datasource;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Aggregation functions applicable to a measure. */
public enum Aggregation {
    SUM("sum"),
    AVG("avg"),
    COUNT("count"),
    MIN("min"),
    MAX("max");

    private final String json;

    Aggregation(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static Aggregation from(String value) {
        if (value != null) {
            for (Aggregation a : values()) {
                if (a.json.equalsIgnoreCase(value) || a.name().equalsIgnoreCase(value)) {
                    return a;
                }
            }
        }
        throw new IllegalArgumentException("Unknown aggregation: " + value);
    }
}
