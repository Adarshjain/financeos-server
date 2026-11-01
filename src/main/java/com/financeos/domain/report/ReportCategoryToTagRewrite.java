package com.financeos.domain.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Rewrites a saved transactions report definition from the old {@code category} field to
 * {@code tag} (V121): the per-user categories became tags, and {@code category} now means the
 * fixed category list, so a saved report must keep reading the tags to keep its numbers. Pure JSON in,
 * JSON out: the migration and its tests run this same code.
 *
 * <p>Only field references are touched, never arbitrary text: {@code dimension}, {@code series} and
 * {@code subDimension} (charts), {@code rows} / {@code columns} (pivot tables: objects with
 * {@code field}; raw tables: plain column names), {@code filters[].field} and {@code sort[].key}.
 * Idempotent on a definition that has no old {@code category} reference left.
 */
public final class ReportCategoryToTagRewrite {

    // Exact decimals: a rewritten definition keeps every number as written (1234.50 stays 1234.50).
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private static final String OLD = "category";
    private static final String NEW = "tag";

    private ReportCategoryToTagRewrite() {
    }

    /** The definition with every {@code category} field reference renamed {@code tag}, or null when nothing changed. */
    public static String rewrite(String definitionJson) {
        if (definitionJson == null) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(definitionJson);
        } catch (Exception e) {
            return null;
        }
        if (!(root instanceof ObjectNode def)) {
            return null;
        }
        boolean changed = false;
        for (String name : new String[] {"dimension", "series", "subDimension"}) {
            changed |= renameField(def.get(name), "field");
        }
        // A chart's "columns" are breakdown column names, not fields: only a raw table (no chartType,
        // no rows) lists plain field names there.
        boolean rawTable = !def.has("chartType") && !def.has("rows") && def.get("columns") instanceof ArrayNode;
        for (String name : new String[] {"rows", "columns", "filters"}) {
            if (def.get(name) instanceof ArrayNode array) {
                for (int i = 0; i < array.size(); i++) {
                    JsonNode element = array.get(i);
                    if (element.isObject()) {
                        changed |= renameField(element, "field");
                    } else if (rawTable && "columns".equals(name) && element.isTextual() && OLD.equals(element.asText())) {
                        array.set(i, TextNode.valueOf(NEW));
                        changed = true;
                    }
                }
            }
        }
        if (def.get("sort") instanceof ArrayNode sort) {
            for (JsonNode clause : sort) {
                changed |= renameField(clause, "key");
            }
        }
        return changed ? def.toString() : null;
    }

    private static boolean renameField(JsonNode node, String property) {
        if (node instanceof ObjectNode o && o.has(property) && OLD.equals(o.get(property).asText(null))) {
            o.put(property, NEW);
            return true;
        }
        return false;
    }
}
