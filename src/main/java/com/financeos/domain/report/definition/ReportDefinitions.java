package com.financeos.domain.report.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.ReportType;

/**
 * Static helpers for (de)serialising {@link ReportDefinition} instances to/from
 * the JSON string stored in {@code reports.definition}.
 */
public final class ReportDefinitions {

    private ReportDefinitions() {
        // utility class
    }

    /**
     * Deserialise {@code json} into the concrete definition record that corresponds
     * to {@code type}.
     *
     * @param type   the report type (determines target class)
     * @param json   the raw JSON string from {@code reports.definition}
     * @param mapper the application {@link ObjectMapper}
     * @return a typed {@link ReportDefinition}
     * @throws IllegalArgumentException if the JSON is malformed or does not match
     *                                  the expected shape for {@code type}
     */
    public static ReportDefinition parse(ReportType type, String json, ObjectMapper mapper) {
        Class<? extends ReportDefinition> targetClass = switch (type) {
            case KPI   -> KpiDefinition.class;
            case CHART -> ChartDefinition.class;
            case TABLE -> TableDefinition.class;
        };
        try {
            return mapper.readValue(json, targetClass);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Invalid " + type + " report definition: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Deserialise a {@link com.fasterxml.jackson.databind.JsonNode} into the concrete
     * definition record that corresponds to {@code type}. Used when the request body
     * carries {@code definition} as a raw {@code JsonNode} (its shape depends on the
     * sibling {@code type} field and cannot be polymorphically bound by Spring directly).
     *
     * @param type   the report type (determines target class)
     * @param node   the raw {@link com.fasterxml.jackson.databind.JsonNode} from the request
     * @param mapper the application {@link ObjectMapper}
     * @return a typed {@link ReportDefinition}
     * @throws IllegalArgumentException if the node does not match the expected shape
     */
    public static ReportDefinition parse(ReportType type, com.fasterxml.jackson.databind.JsonNode node, ObjectMapper mapper) {
        Class<? extends ReportDefinition> targetClass = switch (type) {
            case KPI   -> KpiDefinition.class;
            case CHART -> ChartDefinition.class;
            case TABLE -> TableDefinition.class;
        };
        try {
            return mapper.treeToValue(node, targetClass);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Invalid " + type + " report definition: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Serialise a {@link ReportDefinition} to a JSON string suitable for storage in
     * {@code reports.definition}.
     *
     * @param definition the definition to serialise
     * @param mapper     the application {@link ObjectMapper}
     * @return a JSON string
     * @throws IllegalArgumentException if serialisation fails
     */
    public static String toJson(ReportDefinition definition, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialise report definition: " + e.getOriginalMessage(), e);
        }
    }
}
