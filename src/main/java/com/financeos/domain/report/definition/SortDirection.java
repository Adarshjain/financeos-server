package com.financeos.domain.report.definition;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Sort direction for table sort clauses. */
public enum SortDirection {
    ASC("asc"),
    DESC("desc");

    private final String json;

    SortDirection(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    @JsonCreator
    public static SortDirection from(String value) {
        if (value != null) {
            for (SortDirection d : values()) {
                if (d.json.equalsIgnoreCase(value) || d.name().equalsIgnoreCase(value)) {
                    return d;
                }
            }
        }
        throw new IllegalArgumentException("Unknown sortDirection: " + value);
    }
}
