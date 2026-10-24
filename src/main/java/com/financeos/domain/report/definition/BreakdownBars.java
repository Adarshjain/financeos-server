package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a breakdown chart scales its bars; null means {@code RELATIVE_TO_LARGEST}. */
public enum BreakdownBars {
    RELATIVE_TO_LARGEST("relative_to_largest"),
    SHARE_OF_TOTAL("share_of_total");

    private final String json;

    BreakdownBars(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static BreakdownBars from(String value) {
        if (value != null) {
            for (BreakdownBars o : values()) {
                if (o.json.equalsIgnoreCase(value) || o.name().equalsIgnoreCase(value)) {
                    return o;
                }
            }
        }
        throw new IllegalArgumentException("Unknown breakdown bars: " + value);
    }
}
