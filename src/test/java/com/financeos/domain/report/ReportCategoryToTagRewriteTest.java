package com.financeos.domain.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportCategoryToTagRewriteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode rewritten(String json) throws Exception {
        String out = ReportCategoryToTagRewrite.rewrite(json);
        return out == null ? null : MAPPER.readTree(out);
    }

    @Test
    void chartDimensionSeriesSubDimensionAndFiltersMoveToTag() throws Exception {
        JsonNode out = rewritten("""
                {"chartType":"breakdown","dimension":{"field":"category"},"series":{"field":"category"},
                 "subDimension":{"field":"category"},"measure":{"field":"spend","aggregation":"sum"},
                 "columns":["amount","share"],
                 "filters":[{"field":"category","operator":"in","value":["Dining","category"]},
                            {"field":"date","operator":"this_month"}]}""");

        assertEquals("tag", out.at("/dimension/field").asText());
        assertEquals("tag", out.at("/series/field").asText());
        assertEquals("tag", out.at("/subDimension/field").asText());
        assertEquals("tag", out.at("/filters/0/field").asText());
        // Values and other fields are never touched; a chart's columns are breakdown columns, not fields.
        assertEquals("category", out.at("/filters/0/value/1").asText());
        assertEquals("date", out.at("/filters/1/field").asText());
        assertEquals("amount", out.at("/columns/0").asText());
    }

    @Test
    void pivotRowsColumnsAndSortMoveToTag() throws Exception {
        JsonNode out = rewritten("""
                {"mode":"aggregated","rows":[{"field":"category"},{"field":"account"}],
                 "columns":[{"field":"category"}],"measures":[{"field":"spend","aggregation":"sum"}],
                 "filters":[],"sort":[{"key":"category","direction":"asc"},{"key":"spend_sum","direction":"desc"}]}""");

        assertEquals("tag", out.at("/rows/0/field").asText());
        assertEquals("account", out.at("/rows/1/field").asText());
        assertEquals("tag", out.at("/columns/0/field").asText());
        assertEquals("tag", out.at("/sort/0/key").asText());
        assertEquals("spend_sum", out.at("/sort/1/key").asText());
    }

    @Test
    void rawTableColumnsFiltersAndSortMoveToTag() throws Exception {
        JsonNode out = rewritten("""
                {"mode":"raw","columns":["description","category","spend"],
                 "filters":[{"field":"category","operator":"is_not","value":"Food"}],
                 "sort":[{"key":"category","direction":"desc"}]}""");

        assertEquals("tag", out.at("/columns/1").asText());
        assertEquals("description", out.at("/columns/0").asText());
        assertEquals("tag", out.at("/filters/0/field").asText());
        assertEquals("tag", out.at("/sort/0/key").asText());
    }

    @Test
    void kpiFiltersMoveToTag() throws Exception {
        JsonNode out = rewritten("""
                {"measure":"spend","aggregation":"sum","filters":[{"field":"category","operator":"is","value":"Food"}]}""");

        assertEquals("tag", out.at("/filters/0/field").asText());
        assertEquals("Food", out.at("/filters/0/value").asText());
    }

    @Test
    void textThatMerelyMentionsCategoryIsLeftAlone() {
        assertNull(ReportCategoryToTagRewrite.rewrite("""
                {"chartType":"bar","dimension":{"field":"account"},"measure":{"field":"spend","aggregation":"sum"},
                 "filters":[{"field":"description","operator":"contains","value":"category"}]}"""));
    }

    @Test
    void aSecondPassChangesNothing() {
        String once = ReportCategoryToTagRewrite.rewrite("""
                {"chartType":"bar","dimension":{"field":"category"},"measure":{"field":"spend","aggregation":"sum"},"filters":[]}""");

        assertNull(ReportCategoryToTagRewrite.rewrite(once));
    }

    @Test
    void exactDecimalsSurviveAsWritten() {
        String out = ReportCategoryToTagRewrite.rewrite("""
                {"measure":"spend","aggregation":"sum","filters":[{"field":"category","operator":"is","value":"X"},
                 {"field":"amount","operator":"greater_than","value":1234.50}]}""");

        assertTrue(out.contains("\"value\":1234.50"), out);
    }

    @Test
    void invalidOrEmptyInputIsLeftAlone() {
        assertNull(ReportCategoryToTagRewrite.rewrite(null));
        assertNull(ReportCategoryToTagRewrite.rewrite("not json"));
        assertNull(ReportCategoryToTagRewrite.rewrite("[1,2]"));
    }
}
