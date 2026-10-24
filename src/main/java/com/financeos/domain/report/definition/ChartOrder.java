package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a chart orders its categories (after any top-N fold; "Other" is always last). */
public enum ChartOrder {
    DIMENSION("dimension"),
    VALUE_DESC("value_desc"),
    VALUE_ASC("value_asc");

    private final String json;

    ChartOrder(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static ChartOrder from(String value) {
        if (value != null) {
            for (ChartOrder o : values()) {
                if (o.json.equalsIgnoreCase(value) || o.name().equalsIgnoreCase(value)) {
                    return o;
                }
            }
        }
        throw new IllegalArgumentException("Unknown order: " + value);
    }
}
