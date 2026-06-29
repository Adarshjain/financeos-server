package com.financeos.domain.report.datasource;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a field may participate in a report:
 * <ul>
 *   <li>{@code MEASURE} — aggregated (e.g. amount); eligible for KPI/Chart/Table.</li>
 *   <li>{@code DIMENSION} — grouped/axis/column; eligible for Chart/Table.</li>
 *   <li>{@code FILTER} — only constrains the data; never displayed or grouped.</li>
 * </ul>
 */
public enum FieldRole {
    MEASURE("measure"),
    DIMENSION("dimension"),
    FILTER("filter");

    private final String json;

    FieldRole(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
