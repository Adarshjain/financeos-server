package com.financeos.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.chat.orchestrator.ChatBlocksParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatBlocksParserTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Happy path: all four blocks sections parsed, validated, and unknown fields stripped")
    void happyPathAllBlocksParsed() {
        String json = """
        {
          "unknownField": "should be stripped",
          "stats": [
            {
              "label": "Total Spend",
              "value": "₹42,180",
              "delta": "+12.4% vs July",
              "sentiment": "bad",
              "extra": "ignore"
            }
          ],
          "charts": [
            {
              "chartType": "pie",
              "title": "Spend by Category",
              "categories": ["Dining", "Travel"],
              "series": [
                {
                  "name": "Spend",
                  "data": [12000, 8000],
                  "extraSeriesField": 123
                }
              ],
              "extraChartField": false
            }
          ],
          "tables": [
            {
              "title": "Top Merchants",
              "columns": [
                { "key": "merchant", "label": "Merchant", "align": "left", "format": "text", "extra": "x" },
                { "key": "amount", "label": "Amount", "align": "right", "format": "inr" }
              ],
              "rows": [
                { "merchant": "Swiggy", "amount": 4200, "extraRowKey": "dropped" }
              ]
            }
          ],
          "followUps": [
            "How does this compare to last month?",
            "Which card did I use most for dining?"
          ]
        }
        """;

        JsonNode result = ChatBlocksParser.parse(json, objectMapper);
        assertNotNull(result);
        assertFalse(result.has("unknownField"));

        // Stats
        assertTrue(result.has("stats"));
        JsonNode stats = result.path("stats");
        assertEquals(1, stats.size());
        assertEquals("Total Spend", stats.get(0).path("label").asText());
        assertEquals("₹42,180", stats.get(0).path("value").asText());
        assertEquals("+12.4% vs July", stats.get(0).path("delta").asText());
        assertEquals("bad", stats.get(0).path("sentiment").asText());
        assertFalse(stats.get(0).has("extra"));

        // Charts
        assertTrue(result.has("charts"));
        JsonNode charts = result.path("charts");
        assertEquals(1, charts.size());
        assertEquals("pie", charts.get(0).path("chartType").asText());
        assertEquals("Spend by Category", charts.get(0).path("title").asText());
        assertEquals(2, charts.get(0).path("categories").size());
        JsonNode series = charts.get(0).path("series");
        assertEquals(1, series.size());
        assertEquals("Spend", series.get(0).path("name").asText());
        assertEquals(12000, series.get(0).path("data").get(0).asInt());
        assertEquals(8000, series.get(0).path("data").get(1).asInt());
        assertFalse(charts.get(0).has("extraChartField"));
        assertFalse(series.get(0).has("extraSeriesField"));

        // Tables
        assertTrue(result.has("tables"));
        JsonNode tables = result.path("tables");
        assertEquals(1, tables.size());
        assertEquals("Top Merchants", tables.get(0).path("title").asText());
        JsonNode cols = tables.get(0).path("columns");
        assertEquals(2, cols.size());
        assertEquals("merchant", cols.get(0).path("key").asText());
        assertEquals("left", cols.get(0).path("align").asText());
        assertEquals("text", cols.get(0).path("format").asText());
        assertFalse(cols.get(0).has("extra"));
        assertEquals("amount", cols.get(1).path("key").asText());
        assertEquals("right", cols.get(1).path("align").asText());
        assertEquals("inr", cols.get(1).path("format").asText());

        JsonNode rows = tables.get(0).path("rows");
        assertEquals(1, rows.size());
        assertEquals("Swiggy", rows.get(0).path("merchant").asText());
        assertEquals(4200, rows.get(0).path("amount").asInt());
        assertFalse(rows.get(0).has("extraRowKey"));

        // FollowUps
        assertTrue(result.has("followUps"));
        JsonNode followUps = result.path("followUps");
        assertEquals(2, followUps.size());
        assertEquals("How does this compare to last month?", followUps.get(0).asText());
    }

    @Test
    @DisplayName("Invalid or garbage input returns null")
    void invalidInputReturnsNull() {
        assertNull(ChatBlocksParser.parse(null, objectMapper));
        assertNull(ChatBlocksParser.parse("", objectMapper));
        assertNull(ChatBlocksParser.parse("   ", objectMapper));
        assertNull(ChatBlocksParser.parse("not json", objectMapper));
        assertNull(ChatBlocksParser.parse("[]", objectMapper));
        assertNull(ChatBlocksParser.parse("123", objectMapper));
        assertNull(ChatBlocksParser.parse("{}", objectMapper));
        assertNull(ChatBlocksParser.parse("{\"stats\":[]}", objectMapper));
    }

    @Test
    @DisplayName("Oversized input string (> 20,000 chars) returns null")
    void oversizedInputReturnsNull() {
        String longText = "a".repeat(20_001);
        String json = "{\"followUps\":[\"" + longText + "\"]}";
        assertNull(ChatBlocksParser.parse(json, objectMapper));
    }

    @Test
    @DisplayName("Stats caps and validation: max 4 stats, sentiment normalization, truncate strings")
    void statsCapsAndValidation() {
        String json = """
        {
          "stats": [
            { "label": "S1", "value": "V1", "sentiment": "GOOD" },
            { "label": "S2", "value": "V2", "sentiment": "bad" },
            { "label": "S3", "value": "V3", "sentiment": "invalid_sentiment" },
            { "label": "S4", "value": "V4" },
            { "label": "S5", "value": "V5" },
            { "label": "", "value": "V6" }
          ]
        }
        """;

        JsonNode result = ChatBlocksParser.parse(json, objectMapper);
        assertNotNull(result);
        JsonNode stats = result.path("stats");
        assertEquals(4, stats.size());
        assertEquals("good", stats.get(0).path("sentiment").asText());
        assertEquals("bad", stats.get(1).path("sentiment").asText());
        assertEquals("neutral", stats.get(2).path("sentiment").asText());
        assertEquals("neutral", stats.get(3).path("sentiment").asText());
    }

    @Test
    @DisplayName("Charts validation: whitelist chartType, drop bad charts, pad/truncate series data")
    void chartsValidationAndPadding() {
        String json = """
        {
          "charts": [
            {
              "chartType": "invalid_type",
              "categories": ["A", "B"],
              "series": [{ "name": "S", "data": [1, 2] }]
            },
            {
              "chartType": "bar",
              "title": "Valid Bar Chart",
              "categories": ["Jan", "Feb", "Mar"],
              "series": [
                { "name": "Under", "data": [10, 20] },
                { "name": "Over", "data": [10, 20, 30, 40, 50] },
                { "name": "StringCoerced", "data": ["1,000", null, "bad"] }
              ]
            },
            {
              "chartType": "line",
              "categories": [],
              "series": [{ "name": "S", "data": [1] }]
            }
          ]
        }
        """;

        JsonNode result = ChatBlocksParser.parse(json, objectMapper);
        assertNotNull(result);
        JsonNode charts = result.path("charts");
        assertEquals(1, charts.size());

        JsonNode chart = charts.get(0);
        assertEquals("bar", chart.path("chartType").asText());
        assertEquals("Valid Bar Chart", chart.path("title").asText());
        assertEquals(3, chart.path("categories").size());

        JsonNode series = chart.path("series");
        assertEquals(3, series.size());

        // Under: padded from 2 items to 3 items with null
        JsonNode s0Data = series.get(0).path("data");
        assertEquals(3, s0Data.size());
        assertEquals(10, s0Data.get(0).asInt());
        assertEquals(20, s0Data.get(1).asInt());
        assertTrue(s0Data.get(2).isNull());

        // Over: truncated from 5 items to 3 items
        JsonNode s1Data = series.get(1).path("data");
        assertEquals(3, s1Data.size());
        assertEquals(10, s1Data.get(0).asInt());
        assertEquals(20, s1Data.get(1).asInt());
        assertEquals(30, s1Data.get(2).asInt());

        // StringCoerced: "1,000" -> 1000.0, null -> null, "bad" -> null
        JsonNode s2Data = series.get(2).path("data");
        assertEquals(3, s2Data.size());
        assertEquals(1000.0, s2Data.get(0).asDouble());
        assertTrue(s2Data.get(1).isNull());
        assertTrue(s2Data.get(2).isNull());
    }

    @Test
    @DisplayName("Tables validation: column caps, align/format validation, cell value coercion")
    void tablesValidationAndCoercion() {
        String json = """
        {
          "tables": [
            {
              "title": "Account Breakdown",
              "columns": [
                { "key": "name", "label": "Account", "align": "left", "format": "text" },
                { "key": "balance", "label": "Balance", "align": "RIGHT", "format": "INR" },
                { "key": "active", "label": "Active", "align": "unknown", "format": "unknown" }
              ],
              "rows": [
                { "name": "HDFC Bank", "balance": 150000.50, "active": true },
                { "name": "ICICI Bank", "balance": null, "active": false },
                { "name": "Cash", "balance": "5000", "active": null }
              ]
            }
          ]
        }
        """;

        JsonNode result = ChatBlocksParser.parse(json, objectMapper);
        assertNotNull(result);
        JsonNode tables = result.path("tables");
        assertEquals(1, tables.size());

        JsonNode table = tables.get(0);
        assertEquals("Account Breakdown", table.path("title").asText());

        JsonNode cols = table.path("columns");
        assertEquals(3, cols.size());
        assertEquals("right", cols.get(1).path("align").asText());
        assertEquals("inr", cols.get(1).path("format").asText());
        assertEquals("left", cols.get(2).path("align").asText()); // default fallback
        assertEquals("text", cols.get(2).path("format").asText()); // default fallback

        JsonNode rows = table.path("rows");
        assertEquals(3, rows.size());
        assertEquals(150000.50, rows.get(0).path("balance").asDouble());
        assertTrue(rows.get(0).path("active").asBoolean());
        assertTrue(rows.get(1).path("balance").isNull());
        assertFalse(rows.get(1).path("active").asBoolean());
        assertEquals("5000", rows.get(2).path("balance").asText());
        assertTrue(rows.get(2).path("active").isNull());
    }

    @Test
    @DisplayName("FollowUps caps: max 3 followUps, drops empty strings, truncates to 120 chars")
    void followUpsValidation() {
        String longQuestion = "Q".repeat(150);
        String json = """
        {
          "followUps": [
            "Question 1",
            "",
            "   ",
            "Question 2",
            "%s",
            "Question 4 (should be dropped because cap is 3)"
          ]
        }
        """.formatted(longQuestion);

        JsonNode result = ChatBlocksParser.parse(json, objectMapper);
        assertNotNull(result);
        JsonNode followUps = result.path("followUps");
        assertEquals(3, followUps.size());
        assertEquals("Question 1", followUps.get(0).asText());
        assertEquals("Question 2", followUps.get(1).asText());
        assertEquals(120, followUps.get(2).asText().length());
    }

    @Test
    @DisplayName("ReportDraft create: valid draft survives with opaque definition and sets hasAnyContent")
    void reportDraftCreateHappyPath() {
        String json = """
        {
          "reportDraft": {
            "mode": "create",
            "name": "Monthly Spend",
            "description": "Spend by category",
            "type": "CHART",
            "datasource": "transactions",
            "definition": {
              "chartType": "bar",
              "customOpaqueProperty": 123,
              "nested": { "deepField": true }
            }
          }
        }
        """;

        JsonNode result = ChatBlocksParser.parse(json, objectMapper);
        assertNotNull(result);
        assertTrue(result.has("reportDraft"));

        JsonNode draft = result.path("reportDraft");
        assertEquals("create", draft.path("mode").asText());
        assertEquals("Monthly Spend", draft.path("name").asText());
        assertEquals("Spend by category", draft.path("description").asText());
        assertEquals("CHART", draft.path("type").asText());
        assertEquals("transactions", draft.path("datasource").asText());

        // definition passed opaquely with deep fields preserved
        assertTrue(draft.has("definition"));
        assertEquals(123, draft.path("definition").path("customOpaqueProperty").asInt());
        assertTrue(draft.path("definition").path("nested").path("deepField").asBoolean());
        assertFalse(draft.has("reportId"));
    }

    @Test
    @DisplayName("ReportDraft update: requires valid UUID, bad UUID drops draft while other blocks survive")
    void reportDraftUpdateUuidValidation() {
        // Bad UUID
        String badUuidJson = """
        {
          "stats": [{ "label": "Spend", "value": "100" }],
          "reportDraft": {
            "mode": "update",
            "reportId": "invalid-uuid",
            "name": "Updated Report",
            "type": "KPI",
            "datasource": "transactions",
            "definition": { "measure": "amount" }
          }
        }
        """;

        JsonNode badResult = ChatBlocksParser.parse(badUuidJson, objectMapper);
        assertNotNull(badResult);
        assertTrue(badResult.has("stats"));
        assertFalse(badResult.has("reportDraft"));

        // Good UUID
        String goodUuid = "12345678-1234-1234-1234-123456789abc";
        String goodUuidJson = """
        {
          "reportDraft": {
            "mode": "update",
            "reportId": "%s",
            "name": "Updated Report",
            "type": "KPI",
            "datasource": "transactions",
            "definition": { "measure": "amount" }
          }
        }
        """.formatted(goodUuid);

        JsonNode goodResult = ChatBlocksParser.parse(goodUuidJson, objectMapper);
        assertNotNull(goodResult);
        assertTrue(goodResult.has("reportDraft"));
        assertEquals(goodUuid, goodResult.path("reportDraft").path("reportId").asText());
    }

    @Test
    @DisplayName("ReportDraft delete: requires valid UUID, needs no definition or type")
    void reportDraftDeleteValidation() {
        String uuid = "12345678-1234-1234-1234-123456789abc";
        String json = """
        {
          "reportDraft": {
            "mode": "delete",
            "reportId": "%s",
            "name": "Report to Delete"
          }
        }
        """.formatted(uuid);

        JsonNode result = ChatBlocksParser.parse(json, objectMapper);
        assertNotNull(result);
        assertTrue(result.has("reportDraft"));
        JsonNode draft = result.path("reportDraft");
        assertEquals("delete", draft.path("mode").asText());
        assertEquals(uuid, draft.path("reportId").asText());
        assertEquals("Report to Delete", draft.path("name").asText());
        assertFalse(draft.has("definition"));
        assertFalse(draft.has("type"));
        assertFalse(draft.has("datasource"));
    }

    @Test
    @DisplayName("ReportDraft oversized definition (>8,000 chars) drops the draft")
    void reportDraftOversizedDefinitionDropped() {
        String hugeString = "x".repeat(8100);
        String json = """
        {
          "reportDraft": {
            "mode": "create",
            "name": "Huge Report",
            "type": "KPI",
            "datasource": "transactions",
            "definition": { "large": "%s" }
          }
        }
        """.formatted(hugeString);

        JsonNode result = ChatBlocksParser.parse(json, objectMapper);
        assertNull(result); // With only an invalid reportDraft, returns null
    }
}
