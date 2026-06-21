package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Supported chart visualisation types. */
public enum ChartType {
    LINE("line"),
    BAR("bar"),
    STACKED_BAR("stackedBar"),
    AREA("area"),
    PIE("pie"),
    DONUT("donut");

    private final String json;

    ChartType(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static ChartType from(String value) {
        if (value != null) {
            for (ChartType c : values()) {
                if (c.json.equalsIgnoreCase(value) || c.name().equalsIgnoreCase(value)) {
                    return c;
                }
            }
        }
        throw new IllegalArgumentException("Unknown chartType: " + value);
    }
}
