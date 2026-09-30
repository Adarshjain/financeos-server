package com.financeos.api.report.dto;

import java.util.List;
import java.util.Map;

/**
 * Dynamic enum field name → its values. {@code values} holds the display labels;
 * {@code options} pairs each with the value a filter should store (a stable id for
 * fields with an id, else the label itself).
 */
public record ReportFieldValuesResponse(
        Map<String, List<String>> values,
        Map<String, List<Option>> options) {

    public record Option(String value, String label) {
    }
}
