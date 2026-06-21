package com.financeos.domain.report.datasource;

import com.fasterxml.jackson.annotation.JsonValue;

/** The logical type of a reportable field, which determines its available operators. */
public enum FieldType {
    NUMBER("number"),
    DATE("date"),
    STRING("string"),
    ENUM("enum"),
    BOOLEAN("boolean");

    private final String json;

    FieldType(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
