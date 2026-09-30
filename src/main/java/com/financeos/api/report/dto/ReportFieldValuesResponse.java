package com.financeos.api.report.dto;

import java.util.List;
import java.util.Map;

/** Dynamic enum field name → its selectable values (as the filters compare them). */
public record ReportFieldValuesResponse(Map<String, List<String>> values) {
}
