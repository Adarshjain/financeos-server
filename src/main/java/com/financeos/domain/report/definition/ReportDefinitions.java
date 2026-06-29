package com.financeos.domain.report.definition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.ReportType;

/**
 * Static helpers for (de)serialising {@link ReportDefinition} instances to/from the JSON string
 * stored in {@code reports.definition}. Table definitions are polymorphic: the concrete subtype
 * is chosen from the {@code mode} field.
 */
public final class ReportDefinitions {

    private ReportDefinitions() {
        // utility class
    }

    /** Deserialise a JSON string into the concrete definition record for {@code type}. */
    public static ReportDefinition parse(ReportType type, String json, ObjectMapper mapper) {
        try {
            return parse(type, mapper.readTree(json), mapper);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Invalid " + type + " report definition: " + e.getOriginalMessage(), e);
        }
    }

    /** Deserialise a {@link JsonNode} into the concrete definition record for {@code type}. */
    public static ReportDefinition parse(ReportType type, JsonNode node, ObjectMapper mapper) {
        Class<? extends ReportDefinition> targetClass = switch (type) {
            case KPI -> KpiDefinition.class;
            case CHART -> ChartDefinition.class;
            case TABLE -> tableClass(node);
        };
        try {
            return mapper.treeToValue(node, targetClass);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Invalid " + type + " report definition: " + e.getOriginalMessage(), e);
        }
    }

    /** Serialise a {@link ReportDefinition} to a JSON string for storage. */
    public static String toJson(ReportDefinition definition, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialise report definition: " + e.getOriginalMessage(), e);
        }
    }

    private static Class<? extends ReportDefinition> tableClass(JsonNode node) {
        JsonNode modeNode = node == null ? null : node.get("mode");
        String mode = modeNode == null ? null : modeNode.asText();
        if ("aggregated".equalsIgnoreCase(mode)) {
            return AggregatedTableDefinition.class;
        }
        if ("raw".equalsIgnoreCase(mode)) {
            return RawTableDefinition.class;
        }
        throw new IllegalArgumentException(
                "Table report requires mode 'raw' or 'aggregated' (got: " + mode + ")");
    }
}
