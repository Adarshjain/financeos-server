package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a KPI's comparison line shows: the change against the previous period (the default), or
 * the previous period's own value. The server computes both either way and echoes the chosen
 * display on the response so the renderer needs no access to the definition.
 */
public enum ComparisonDisplay {
    CHANGE("change"),
    PREVIOUS_VALUE("previous_value");

    private final String json;

    ComparisonDisplay(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static ComparisonDisplay from(String value) {
        if (value != null) {
            for (ComparisonDisplay d : values()) {
                if (d.json.equalsIgnoreCase(value) || d.name().equalsIgnoreCase(value)) {
                    return d;
                }
            }
        }
        throw new IllegalArgumentException("Unknown comparison display: " + value);
    }

    /** The display for a comparison block; {@link #CHANGE} when the block or its display is absent. */
    public static ComparisonDisplay resolve(Comparison comparison) {
        return comparison == null || comparison.display() == null ? CHANGE : comparison.display();
    }
}
