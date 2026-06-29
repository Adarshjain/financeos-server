package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Operating mode of a table report. */
public enum TableMode {
    RAW("raw"),
    AGGREGATED("aggregated");

    private final String json;

    TableMode(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static TableMode from(String value) {
        if (value != null) {
            for (TableMode m : values()) {
                if (m.json.equalsIgnoreCase(value) || m.name().equalsIgnoreCase(value)) {
                    return m;
                }
            }
        }
        throw new IllegalArgumentException("Unknown tableMode: " + value);
    }
}
