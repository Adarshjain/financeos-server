package com.financeos.chat.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ChatBlocksParser {

    private static final Logger log = LoggerFactory.getLogger(ChatBlocksParser.class);

    private static final int MAX_INPUT_CHARS = 20_000;
    private static final int MAX_STATS = 4;
    private static final int MAX_CHARTS = 2;
    private static final int MAX_TABLES = 2;
    private static final int MAX_FOLLOW_UPS = 3;

    private static final Set<String> ALLOWED_SENTIMENTS = Set.of("good", "bad", "neutral");
    private static final Set<String> ALLOWED_CHART_TYPES = Set.of("bar", "stackedBar", "line", "area", "pie", "donut");
    private static final Set<String> ALLOWED_ALIGNMENTS = Set.of("left", "right");
    private static final Set<String> ALLOWED_FORMATS = Set.of("inr", "number", "text");

    private ChatBlocksParser() {}

    /**
     * Parses and sanitizes a JSON-encoded blocks string into a clean ObjectNode.
     * Drops unknown/hostile fields, enforces structural limits, and returns null if the
     * input is invalid or contains zero valid blocks.
     */
    public static ObjectNode parse(String blocksJson, ObjectMapper om) {
        if (blocksJson == null || om == null) {
            return null;
        }
        String trimmed = blocksJson.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_INPUT_CHARS) {
            return null;
        }

        try {
            JsonNode rootNode = om.readTree(trimmed);
            if (!rootNode.isObject()) {
                return null;
            }

            ObjectNode sanitizedRoot = om.createObjectNode();
            boolean hasAnyContent = false;

            // 1. Stats
            ArrayNode statsArray = parseStats(rootNode.path("stats"), om);
            if (statsArray != null && !statsArray.isEmpty()) {
                sanitizedRoot.set("stats", statsArray);
                hasAnyContent = true;
            }

            // 2. Charts
            ArrayNode chartsArray = parseCharts(rootNode.path("charts"), om);
            if (chartsArray != null && !chartsArray.isEmpty()) {
                sanitizedRoot.set("charts", chartsArray);
                hasAnyContent = true;
            }

            // 3. Tables
            ArrayNode tablesArray = parseTables(rootNode.path("tables"), om);
            if (tablesArray != null && !tablesArray.isEmpty()) {
                sanitizedRoot.set("tables", tablesArray);
                hasAnyContent = true;
            }

            // 4. Follow-ups
            ArrayNode followUpsArray = parseFollowUps(rootNode.path("followUps"), om);
            if (followUpsArray != null && !followUpsArray.isEmpty()) {
                sanitizedRoot.set("followUps", followUpsArray);
                hasAnyContent = true;
            }

            return hasAnyContent ? sanitizedRoot : null;

        } catch (Exception e) {
            log.debug("Failed to parse chat blocks JSON: {}", e.getMessage());
            return null;
        }
    }

    private static ArrayNode parseStats(JsonNode statsNode, ObjectMapper om) {
        if (!statsNode.isArray()) {
            return null;
        }
        ArrayNode result = om.createArrayNode();
        for (JsonNode item : statsNode) {
            if (result.size() >= MAX_STATS) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }
            String label = item.path("label").asText("").trim();
            String value = item.path("value").asText("").trim();
            if (label.isEmpty() || value.isEmpty()) {
                continue;
            }

            ObjectNode statObj = om.createObjectNode();
            statObj.put("label", truncate(label, 80));
            statObj.put("value", truncate(value, 40));

            if (item.hasNonNull("delta")) {
                String delta = item.path("delta").asText("").trim();
                if (!delta.isEmpty()) {
                    statObj.put("delta", truncate(delta, 60));
                }
            }

            String sentiment = item.path("sentiment").asText("neutral").trim().toLowerCase();
            statObj.put("sentiment", ALLOWED_SENTIMENTS.contains(sentiment) ? sentiment : "neutral");

            result.add(statObj);
        }
        return result;
    }

    private static ArrayNode parseCharts(JsonNode chartsNode, ObjectMapper om) {
        if (!chartsNode.isArray()) {
            return null;
        }
        ArrayNode result = om.createArrayNode();
        for (JsonNode item : chartsNode) {
            if (result.size() >= MAX_CHARTS) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }

            String chartType = item.path("chartType").asText("").trim();
            if (!ALLOWED_CHART_TYPES.contains(chartType)) {
                continue;
            }

            // Categories
            JsonNode categoriesNode = item.path("categories");
            if (!categoriesNode.isArray() || categoriesNode.isEmpty()) {
                continue;
            }
            List<String> categories = new ArrayList<>();
            for (JsonNode cat : categoriesNode) {
                if (categories.size() >= 50) {
                    break;
                }
                categories.add(truncate(cat.asText(""), 80));
            }
            if (categories.isEmpty()) {
                continue;
            }

            // Series
            JsonNode seriesNode = item.path("series");
            if (!seriesNode.isArray() || seriesNode.isEmpty()) {
                continue;
            }
            ArrayNode sanitizedSeries = om.createArrayNode();
            for (JsonNode s : seriesNode) {
                if (sanitizedSeries.size() >= 6) {
                    break;
                }
                if (!s.isObject()) {
                    continue;
                }
                String name = s.path("name").asText("Series").trim();
                ObjectNode sObj = om.createObjectNode();
                sObj.put("name", truncate(name.isEmpty() ? "Series" : name, 80));

                ArrayNode dataArray = om.createArrayNode();
                JsonNode rawData = s.path("data");
                int catCount = categories.size();

                for (int i = 0; i < catCount; i++) {
                    if (rawData.isArray() && i < rawData.size()) {
                        JsonNode val = rawData.get(i);
                        if (val == null || val.isNull()) {
                            dataArray.addNull();
                        } else if (val.isNumber()) {
                            if (val.isIntegralNumber()) {
                                dataArray.add(val.asLong());
                            } else {
                                dataArray.add(val.asDouble());
                            }
                        } else {
                            try {
                                double d = Double.parseDouble(val.asText().replace(",", "").trim());
                                dataArray.add(d);
                            } catch (Exception e) {
                                dataArray.addNull();
                            }
                        }
                    } else {
                        // Pad to categories.length
                        dataArray.addNull();
                    }
                }
                sObj.set("data", dataArray);
                sanitizedSeries.add(sObj);
            }

            if (sanitizedSeries.isEmpty()) {
                continue;
            }

            ObjectNode chartObj = om.createObjectNode();
            chartObj.put("chartType", chartType);
            if (item.hasNonNull("title")) {
                String title = item.path("title").asText("").trim();
                if (!title.isEmpty()) {
                    chartObj.put("title", truncate(title, 80));
                }
            }

            ArrayNode catsArray = om.createArrayNode();
            for (String cat : categories) {
                catsArray.add(cat);
            }
            chartObj.set("categories", catsArray);
            chartObj.set("series", sanitizedSeries);

            result.add(chartObj);
        }
        return result;
    }

    private static ArrayNode parseTables(JsonNode tablesNode, ObjectMapper om) {
        if (!tablesNode.isArray()) {
            return null;
        }
        ArrayNode result = om.createArrayNode();
        for (JsonNode item : tablesNode) {
            if (result.size() >= MAX_TABLES) {
                break;
            }
            if (!item.isObject()) {
                continue;
            }

            // Columns
            JsonNode columnsNode = item.path("columns");
            if (!columnsNode.isArray() || columnsNode.isEmpty()) {
                continue;
            }
            ArrayNode sanitizedColumns = om.createArrayNode();
            List<String> colKeys = new ArrayList<>();

            for (JsonNode col : columnsNode) {
                if (sanitizedColumns.size() >= 8) {
                    break;
                }
                if (!col.isObject()) {
                    continue;
                }
                String key = col.path("key").asText("").trim();
                if (key.isEmpty()) {
                    continue;
                }
                String label = col.path("label").asText(key).trim();
                if (label.isEmpty()) {
                    label = key;
                }

                ObjectNode colObj = om.createObjectNode();
                colObj.put("key", truncate(key, 60));
                colObj.put("label", truncate(label, 60));

                String align = col.path("align").asText("left").trim().toLowerCase();
                if (ALLOWED_ALIGNMENTS.contains(align)) {
                    colObj.put("align", align);
                } else {
                    colObj.put("align", "left");
                }

                String format = col.path("format").asText("text").trim().toLowerCase();
                if (ALLOWED_FORMATS.contains(format)) {
                    colObj.put("format", format);
                } else {
                    colObj.put("format", "text");
                }

                sanitizedColumns.add(colObj);
                colKeys.add(key);
            }

            if (sanitizedColumns.isEmpty()) {
                continue;
            }

            // Rows
            JsonNode rowsNode = item.path("rows");
            if (!rowsNode.isArray() || rowsNode.isEmpty()) {
                continue;
            }
            ArrayNode sanitizedRows = om.createArrayNode();
            for (JsonNode r : rowsNode) {
                if (sanitizedRows.size() >= 50) {
                    break;
                }
                if (!r.isObject()) {
                    continue;
                }
                ObjectNode rowObj = om.createObjectNode();
                for (String key : colKeys) {
                    JsonNode cell = r.get(key);
                    if (cell == null || cell.isNull() || cell.isMissingNode()) {
                        rowObj.putNull(key);
                    } else if (cell.isNumber()) {
                        if (cell.isIntegralNumber()) {
                            rowObj.put(key, cell.asLong());
                        } else {
                            rowObj.put(key, cell.asDouble());
                        }
                    } else if (cell.isBoolean()) {
                        rowObj.put(key, cell.asBoolean());
                    } else if (cell.isTextual()) {
                        rowObj.put(key, cell.asText());
                    } else {
                        rowObj.put(key, cell.asText());
                    }
                }
                sanitizedRows.add(rowObj);
            }

            if (sanitizedRows.isEmpty()) {
                continue;
            }

            ObjectNode tableObj = om.createObjectNode();
            if (item.hasNonNull("title")) {
                String title = item.path("title").asText("").trim();
                if (!title.isEmpty()) {
                    tableObj.put("title", truncate(title, 80));
                }
            }
            tableObj.set("columns", sanitizedColumns);
            tableObj.set("rows", sanitizedRows);

            result.add(tableObj);
        }
        return result;
    }

    private static ArrayNode parseFollowUps(JsonNode followUpsNode, ObjectMapper om) {
        if (!followUpsNode.isArray()) {
            return null;
        }
        ArrayNode result = om.createArrayNode();
        for (JsonNode item : followUpsNode) {
            if (result.size() >= MAX_FOLLOW_UPS) {
                break;
            }
            String text = item.asText("").trim();
            if (!text.isEmpty()) {
                result.add(truncate(text, 120));
            }
        }
        return result;
    }

    private static String truncate(String val, int maxLen) {
        if (val == null) {
            return "";
        }
        return val.length() <= maxLen ? val : val.substring(0, maxLen);
    }
}
