package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A column a breakdown chart's list can show; {@code CHANGE} is the change vs the previous period. */
public enum BreakdownColumn {
    AMOUNT("amount"),
    SHARE("share"),
    COUNT("count"),
    AVG("avg"),
    CHANGE("change");

    private final String json;

    BreakdownColumn(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static BreakdownColumn from(String value) {
        if (value != null) {
            for (BreakdownColumn o : values()) {
                if (o.json.equalsIgnoreCase(value) || o.name().equalsIgnoreCase(value)) {
                    return o;
                }
            }
        }
        throw new IllegalArgumentException("Unknown breakdown column: " + value);
    }
}
