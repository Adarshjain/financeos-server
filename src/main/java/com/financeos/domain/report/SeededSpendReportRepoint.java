package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * V124: the Home dashboard's seeded "Spend this month" report was rewritten from {@code category} to
 * {@code tag} by V121 like every other report, while new users get it on the fixed {@code category}
 * list. This re-points the existing seeded copies. It is a guess-free match: exact seeded name and
 * description, a chart on the transactions datasource, and a definition that is still exactly the seeded
 * shape (bar chart, dimension {@code tag}, spend sum, only the seeded filters with the seeded values and a
 * this-month date filter). Anything the user edited does not match and stays on {@code tag}.
 */
public final class SeededSpendReportRepoint {

    public static final String NAME = "Spend this month";
    public static final String DESCRIPTION = "Debits this month, by category";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SeededSpendReportRepoint() {
    }

    /** The definition re-pointed to {@code category}, or null when this is not an unedited seeded copy. */
    public static String repoint(String name, String description, String type, String datasource, String definitionJson) {
        if (!NAME.equals(name) || !DESCRIPTION.equals(description) || !"CHART".equalsIgnoreCase(type)
                || !"transactions".equals(datasource) || definitionJson == null) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(definitionJson);
        } catch (Exception e) {
            return null;
        }
        if (!(root instanceof ObjectNode def)
                || !"bar".equalsIgnoreCase(def.path("chartType").asText())
                || !"tag".equals(def.path("dimension").path("field").asText())
                || !"spend".equals(def.path("measure").path("field").asText())
                || !"sum".equalsIgnoreCase(def.path("measure").path("aggregation").asText())
                || !isEmpty(def.get("series")) || !isEmpty(def.get("subDimension"))
                || !isEmpty(def.get("sort")) || !def.path("filters").isArray()) {
            return null;
        }
        boolean hasDate = false;
        for (JsonNode f : def.get("filters")) {
            String field = f.path("field").asText();
            String op = f.path("operator").asText();
            JsonNode value = f.get("value");
            if ("date".equals(field) && "this_month".equals(op)) {
                hasDate = true;
            } else if ("type".equals(field) && "is".equals(op) && value != null && "DEBIT".equals(value.asText())) {
                continue;
            } else if (Set.of("isExcluded", "isTransferLeg").contains(field) && "is".equals(op)
                    && value != null && value.isBoolean() && !value.asBoolean()) {
                continue;
            } else {
                return null;
            }
        }
        if (!hasDate) {
            return null;
        }
        ((ObjectNode) def.get("dimension")).put("field", "category");
        return def.toString();
    }

    private static boolean isEmpty(JsonNode n) {
        return n == null || n.isNull() || ((n.isArray() || n.isObject()) && n.isEmpty());
    }
}
