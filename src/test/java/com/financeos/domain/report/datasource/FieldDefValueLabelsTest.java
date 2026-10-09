package com.financeos.domain.report.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Display labels for a static enum field's stored values: built in order, kept by the other withers, display only. */
class FieldDefValueLabelsTest {

    private static final List<ReportType> CHART_TABLE = List.of(ReportType.CHART, ReportType.TABLE);
    private final ObjectMapper mapper = new ObjectMapper();

    private static FieldDef side() {
        return new FieldDef("side", "Side", FieldType.ENUM, FieldRole.DIMENSION, null, List.of("asset", "liability"),
                null, CHART_TABLE);
    }

    @Test
    void valueLabelsComeInTheGivenOrder() {
        Map<String, String> labels = DatasourceCatalog.valueLabels("z", "Zed", "a", "Ay", "m", "Em");

        assertEquals(List.of("z", "a", "m"), new ArrayList<>(labels.keySet()));
        assertEquals(List.of("Zed", "Ay", "Em"), new ArrayList<>(labels.values()));
        assertThrows(UnsupportedOperationException.class, () -> labels.put("x", "X"));
    }

    @Test
    void valueLabelsMustBeValueLabelPairs() {
        assertThrows(IllegalArgumentException.class, () -> DatasourceCatalog.valueLabels("asset", "Asset", "liability"));
    }

    @Test
    void aFieldHasNoValueLabelsUntilGivenThem() {
        assertNull(side().valueLabels());
    }

    @Test
    void withValueLabelsKeepsEveryOtherPartOfTheFieldAndTheLabelOrder() {
        FieldDef field = side();

        FieldDef labelled = field.withValueLabels(DatasourceCatalog.valueLabels("liability", "Liability", "asset", "Asset"));

        assertEquals(List.of("liability", "asset"), new ArrayList<>(labelled.valueLabels().keySet()));
        assertEquals(field, new FieldDef(labelled.name(), labelled.label(), labelled.type(), labelled.role(),
                labelled.aggregations(), labelled.values(), labelled.dynamic(), labelled.allowedInReports(),
                labelled.format(), labelled.idField(), labelled.billingCycle(), labelled.filterable()));
        assertThrows(UnsupportedOperationException.class, () -> labelled.valueLabels().put("x", "X"));
    }

    @Test
    void notFilterableKeepsTheValueLabelsAndWithValueLabelsKeepsNotFilterable() {
        Map<String, String> labels = DatasourceCatalog.valueLabels("asset", "Asset");

        FieldDef labelledThenHidden = side().withValueLabels(labels).notFilterable();
        FieldDef hiddenThenLabelled = side().notFilterable().withValueLabels(labels);

        assertEquals(labels, labelledThenHidden.valueLabels());
        assertFalse(labelledThenHidden.canFilter());
        assertEquals(labels, hiddenThenLabelled.valueLabels());
        assertFalse(hiddenThenLabelled.canFilter());
    }

    @Test
    void displayValueReadsAStoredValueByItsLabelAndPassesEverythingElseThrough() {
        FieldDef labelled = side().withValueLabels(DatasourceCatalog.valueLabels("asset", "Asset"));
        Object number = 5;

        assertEquals("Asset", labelled.displayValue("asset"));
        assertEquals("other", labelled.displayValue("other"));
        assertSame(number, labelled.displayValue(number));
        assertNull(labelled.displayValue(null));
        assertEquals("asset", side().displayValue("asset"));
    }

    @Test
    void jsonListsValueLabelsInOrderAndOmitsThemWhenAbsent() throws Exception {
        JsonNode plain = mapper.valueToTree(side());
        JsonNode labelled = mapper.valueToTree(side().withValueLabels(
                DatasourceCatalog.valueLabels("liability", "Liability", "asset", "Asset")));

        assertFalse(plain.has("valueLabels"));
        assertEquals(List.of("liability", "asset"), fieldNames(labelled.get("valueLabels")));
        assertEquals("Liability", labelled.get("valueLabels").get("liability").asText());
        assertEquals("Asset", labelled.get("valueLabels").get("asset").asText());
        assertTrue(labelled.get("values").isArray());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
