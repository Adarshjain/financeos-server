package com.financeos.domain.report.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.datasource.DatasourceCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The additive display fields of report results serialize only when set: value labels on table
 * columns, pivot dimensions and charts, and the runtime sort echo on a table page.
 */
class ReportResultLabelsJsonTest {

    private static final Map<String, String> SIDES = DatasourceCatalog.valueLabels("asset", "Asset", "liability", "Liability");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aColumnListsItsValueLabelsOnlyWhenItHasThem() {
        JsonNode plain = mapper.valueToTree(new TableData.Column("name", "Name", "string", null));
        JsonNode labelled = mapper.valueToTree(new TableData.Column("side", "Side", "enum", null, SIDES));

        assertFalse(plain.has("valueLabels"));
        assertEquals("Asset", labelled.get("valueLabels").get("asset").asText());
        assertEquals("Liability", labelled.get("valueLabels").get("liability").asText());
        assertNull(new TableData.Column("name", "Name", "string", null).valueLabels());
    }

    @Test
    void aTablePageEchoesItsSortOnlyWhenSorted() {
        TableData.Page page = new TableData.Page(0, 25, 0, 1);
        JsonNode unsorted = mapper.valueToTree(new TableData("TABLE", "raw", List.of(), List.of(), page));
        JsonNode sorted = mapper.valueToTree(new TableData("TABLE", "raw", List.of(), List.of(), page, "amount", "desc"));

        assertFalse(unsorted.has("sortKey"));
        assertFalse(unsorted.has("sortDirection"));
        assertEquals("amount", sorted.get("sortKey").asText());
        assertEquals("desc", sorted.get("sortDirection").asText());
    }

    @Test
    void aPivotDimensionListsItsValueLabelsOnlyWhenItHasThem() {
        JsonNode plain = mapper.valueToTree(new PivotTableData.DimensionInfo("name", "Name"));
        JsonNode labelled = mapper.valueToTree(new PivotTableData.DimensionInfo("side", "Side", SIDES));

        assertFalse(plain.has("valueLabels"));
        assertEquals("Asset", labelled.get("valueLabels").get("asset").asText());
    }

    @Test
    void aChartListsCategoryAndSeriesValueLabelsOnlyWhenItHasThem() {
        ChartData.MeasureView measure = new ChartData.MeasureView("value", "sum");
        ChartData.Meta meta = new ChartData.Meta(0, null);
        JsonNode plain = mapper.valueToTree(new ChartData("CHART", "bar", "name", List.of(), List.of(), measure, meta));
        JsonNode labelled = mapper.valueToTree(new ChartData("CHART", "bar", "side", List.of("asset"), List.of(), measure,
                meta, SIDES, Map.of("bank_account", "Bank account")));

        assertFalse(plain.has("valueLabels"));
        assertFalse(plain.has("seriesValueLabels"));
        assertEquals("Asset", labelled.get("valueLabels").get("asset").asText());
        assertEquals("Bank account", labelled.get("seriesValueLabels").get("bank_account").asText());
    }
}
